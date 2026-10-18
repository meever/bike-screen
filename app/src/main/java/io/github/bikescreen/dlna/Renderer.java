package io.github.bikescreen.dlna;

import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.*;
import java.util.concurrent.*;
import java.util.regex.*;

/**
 * Minimal UPnP/DLNA MediaRenderer: SSDP discovery, device + service descriptions,
 * SOAP control for AVTransport / RenderingControl / ConnectionManager, and GENA
 * LastChange events. Plain Java so it runs on the bike and on a desktop JVM alike.
 */
public final class Renderer {
    public interface Log { void log(String msg); }
    /** Handles GET /cmd/<rest>; returns plain text. */
    public interface Command { String run(String rest); }

    static final String SSDP_ADDR = "239.255.255.250";
    static final int SSDP_PORT = 1900;
    static final String DEVICE_TYPE = "urn:schemas-upnp-org:device:MediaRenderer:1";
    static final String AVT = "urn:schemas-upnp-org:service:AVTransport:1";
    static final String RC = "urn:schemas-upnp-org:service:RenderingControl:1";
    static final String CM = "urn:schemas-upnp-org:service:ConnectionManager:1";
    static final String SERVER = "Android/1 UPnP/1.0 bike-dlna/1";

    private final String name, uuid;
    private final Player player;
    private final InetAddress ip;
    private final Log log;
    private final ExecutorService pool = Executors.newCachedThreadPool();
    private final Map<String, Sub> subs = new ConcurrentHashMap<>();
    private volatile boolean running;
    private volatile Command command = rest -> "no commands\n";
    private volatile boolean debugEndpoints;
    private volatile String uri = "", meta = "";
    private ServerSocket http;
    private MulticastSocket ssdp;
    private int port;

    public Renderer(String name, String uuid, Player player, InetAddress ip, Log log) {
        this.name = name; this.uuid = uuid; this.player = player; this.ip = ip;
        this.log = m -> { m = redact(m); note(m); log.log(m); };
    }

    // Last lines of the log, served at GET /log so debugging needs no adb.
    private static final ArrayDeque<String> journal = new ArrayDeque<>();

    public static void note(String m) {
        String line = new SimpleDateFormat("HH:mm:ss.SSS", Locale.US).format(new Date()) + " " + m;
        synchronized (journal) { journal.addLast(line); while (journal.size() > 1000) journal.removeFirst(); }
    }

    private static String journal() {
        synchronized (journal) { return String.join("\n", journal) + "\n"; }
    }

    public void start() throws IOException {
        running = true;
        http = new ServerSocket();
        http.setReuseAddress(true);
        try { http.bind(new InetSocketAddress(49152)); } catch (IOException e) { http.bind(new InetSocketAddress(0)); }
        port = http.getLocalPort();

        ssdp = new MulticastSocket(null);
        ssdp.setReuseAddress(true);
        ssdp.bind(new InetSocketAddress(SSDP_PORT));
        NetworkInterface nif = NetworkInterface.getByInetAddress(ip);
        ssdp.setNetworkInterface(nif);
        ssdp.joinGroup(new InetSocketAddress(SSDP_ADDR, SSDP_PORT), nif);
        ssdp.setTimeToLive(4);

        pool.execute(this::httpLoop);
        pool.execute(this::ssdpLoop);
        pool.execute(this::notifyLoop);
        pool.execute(this::eventLoop);
        log.log("renderer '" + name + "' at " + location());
    }

    public void stop() {
        running = false;
        try { for (String[] nt : targets()) sendMulticast(notify(nt[0], nt[1], "ssdp:byebye")); } catch (Exception ignored) {}
        try { http.close(); } catch (Exception ignored) {}
        try { ssdp.close(); } catch (Exception ignored) {}
        pool.shutdownNow();
    }

    public void setCommand(Command c) { command = c; }

    /** Serve GET /log and GET /cmd/... — unauthenticated, so off unless the build opts in. */
    public void setDebugEndpoints(boolean on) { debugEndpoints = on; }

    /** Cast URLs carry signed tokens; never let a query string reach a log. */
    static String redact(String m) { return m.replaceAll("(https?://[^\\s<>\"?&]+)\\?[^\\s<>\"]*", "$1?…"); }

    private String location() { return "http://" + ip.getHostAddress() + ":" + port + "/desc.xml"; }

    // ---------------------------------------------------------------- SSDP

    /** Every (NT/ST, USN) pair this device advertises. */
    private List<String[]> targets() {
        String u = "uuid:" + uuid;
        List<String[]> t = new ArrayList<>();
        t.add(new String[]{"upnp:rootdevice", u + "::upnp:rootdevice"});
        t.add(new String[]{u, u});
        for (String s : new String[]{DEVICE_TYPE, AVT, RC, CM}) t.add(new String[]{s, u + "::" + s});
        return t;
    }

    private String notify(String nt, String usn, String nts) {
        return "NOTIFY * HTTP/1.1\r\nHOST: " + SSDP_ADDR + ":" + SSDP_PORT + "\r\nCACHE-CONTROL: max-age=1800\r\n"
                + "LOCATION: " + location() + "\r\nNT: " + nt + "\r\nNTS: " + nts + "\r\nSERVER: " + SERVER
                + "\r\nUSN: " + usn + "\r\n\r\n";
    }

    private void sendMulticast(String msg) throws IOException {
        byte[] b = msg.getBytes(StandardCharsets.UTF_8);
        ssdp.send(new DatagramPacket(b, b.length, InetAddress.getByName(SSDP_ADDR), SSDP_PORT));
    }

    private void notifyLoop() {
        while (running) {
            try { for (String[] nt : targets()) sendMulticast(notify(nt[0], nt[1], "ssdp:alive")); }
            catch (Exception e) { if (running) log.log("notify: " + e); }
            try { Thread.sleep(10_000); } catch (InterruptedException e) { return; }
        }
    }

    private void ssdpLoop() {
        byte[] buf = new byte[4096];
        while (running) {
            try {
                DatagramPacket p = new DatagramPacket(buf, buf.length);
                ssdp.receive(p);
                String msg = new String(p.getData(), 0, p.getLength(), StandardCharsets.UTF_8);
                if (!msg.startsWith("M-SEARCH")) continue;
                String st = header(msg, "ST");
                if (st == null) continue;
                List<String[]> hits = new ArrayList<>();
                for (String[] t : targets()) if (st.equals("ssdp:all") || st.equals(t[0])) hits.add(t);
                if (hits.isEmpty()) continue;
                log.log("M-SEARCH " + st + " from " + p.getAddress().getHostAddress());
                String date = httpDate();
                try (DatagramSocket out = new DatagramSocket()) {
                    for (String[] t : hits) {
                        byte[] b = ("HTTP/1.1 200 OK\r\nCACHE-CONTROL: max-age=1800\r\nDATE: " + date + "\r\nEXT:\r\n"
                                + "LOCATION: " + location() + "\r\nSERVER: " + SERVER + "\r\nST: " + t[0]
                                + "\r\nUSN: " + t[1] + "\r\n\r\n").getBytes(StandardCharsets.UTF_8);
                        out.send(new DatagramPacket(b, b.length, p.getAddress(), p.getPort()));
                    }
                }
            } catch (Exception e) { if (running) log.log("ssdp: " + e); }
        }
    }

    // ---------------------------------------------------------------- HTTP

    private void httpLoop() {
        while (running) {
            try { Socket s = http.accept(); pool.execute(() -> serve(s)); }
            catch (Exception e) { if (running) log.log("accept: " + e); }
        }
    }

    private void serve(Socket s) {
        try (Socket sock = s) {
            sock.setSoTimeout(10_000);
            InputStream in = new BufferedInputStream(sock.getInputStream());
            String head = readHead(in);
            if (head.isEmpty()) return;
            String[] line = head.substring(0, head.indexOf("\r\n")).split(" ");
            String method = line[0], path = line.length > 1 ? line[1] : "/";
            int len = 0;
            String cl = header(head, "CONTENT-LENGTH");
            if (cl != null) len = Integer.parseInt(cl.trim());
            byte[] body = new byte[len];
            for (int off = 0; off < len; ) { int n = in.read(body, off, len - off); if (n < 0) break; off += n; }
            String req = new String(body, StandardCharsets.UTF_8);
            OutputStream out = sock.getOutputStream();

            if (!debugEndpoints && method.equals("GET") && (path.startsWith("/cmd/") || path.startsWith("/log"))) {
                respond(out, "404 Not Found", null, "", null);
            } else if (method.equals("GET") && path.startsWith("/cmd/")) {
                log.log("cmd " + path);
                respond(out, "200 OK", "text/plain; charset=utf-8", command.run(path.substring(5)), null);
            } else if (method.equals("GET") && path.startsWith("/log")) {
                respond(out, "200 OK", "text/plain; charset=utf-8", journal(), null);
            } else if (method.equals("GET") || method.equals("HEAD")) {
                String xml = path.startsWith("/desc.xml") ? deviceXml()
                        : path.startsWith("/AVTransport.xml") ? Scpd.avTransport()
                        : path.startsWith("/RenderingControl.xml") ? Scpd.renderingControl()
                        : path.startsWith("/ConnectionManager.xml") ? Scpd.connectionManager() : null;
                log.log(method + " " + path + " from " + sock.getInetAddress().getHostAddress());
                if (xml == null) respond(out, "404 Not Found", null, "", null);
                else respond(out, "200 OK", "text/xml; charset=\"utf-8\"", xml, null);
            } else if (method.equals("POST")) {
                String action = header(head, "SOAPACTION");
                action = action == null ? "" : action.replace("\"", "");
                String service = action.contains("#") ? action.substring(0, action.indexOf('#')) : "";
                String act = action.substring(action.indexOf('#') + 1);
                log.log("SOAP " + act + " " + req.replaceAll("\\s+", " ")
                        .replaceAll(".*<s:Body>|.*<SOAP-ENV:Body>|</s:Body>.*|</SOAP-ENV:Body>.*", ""));
                try {
                    String outArgs = control(service, act, req);
                    respond(out, "200 OK", "text/xml; charset=\"utf-8\"", envelope("<u:" + act + "Response xmlns:u=\""
                            + service + "\">" + outArgs + "</u:" + act + "Response>"), null);
                } catch (UpnpError e) {
                    log.log("SOAP error " + e.code + " " + e.getMessage());
                    respond(out, "500 Internal Server Error", "text/xml; charset=\"utf-8\"", envelope(
                            "<s:Fault><faultcode>s:Client</faultcode><faultstring>UPnPError</faultstring><detail>"
                            + "<UPnPError xmlns=\"urn:schemas-upnp-org:control-1-0\"><errorCode>" + e.code
                            + "</errorCode><errorDescription>" + esc(e.getMessage())
                            + "</errorDescription></UPnPError></detail></s:Fault>"), null);
                }
            } else if (method.equals("SUBSCRIBE")) {
                String sid = header(head, "SID"), cb = header(head, "CALLBACK");
                String svc = path.startsWith("/AVTransport") ? AVT : path.startsWith("/RenderingControl") ? RC : CM;
                if (sid == null || !subs.containsKey(sid)) {
                    sid = "uuid:" + UUID.randomUUID();
                    String url = cb == null ? null : cb.replaceAll("^\\s*<([^>]*)>.*$", "$1");
                    if (url != null) subs.put(sid, new Sub(svc, url));
                }
                log.log("SUBSCRIBE " + path + " -> " + sid);
                respond(out, "200 OK", null, "", "SID: " + sid + "\r\nTIMEOUT: Second-1800\r\n");
                Sub sub = subs.get(sid);
                if (sub != null) { String id = sid; pool.execute(() -> sendEvent(id, sub)); }
            } else if (method.equals("UNSUBSCRIBE")) {
                String sid = header(head, "SID");
                if (sid != null) subs.remove(sid);
                respond(out, "200 OK", null, "", null);
            } else {
                log.log("unhandled " + method + " " + path);
                respond(out, "405 Method Not Allowed", null, "", null);
            }
        } catch (Exception e) { log.log("http: " + e); }
    }

    private static String readHead(InputStream in) throws IOException {
        ByteArrayOutputStream b = new ByteArrayOutputStream();
        int c, tail = 0;
        while ((c = in.read()) >= 0) {
            b.write(c);
            tail = (tail << 8 | c) & 0xFFFFFFFF;
            if (tail == 0x0D0A0D0A) break;   // \r\n\r\n
        }
        return b.toString("UTF-8");
    }

    private static void respond(OutputStream out, String status, String type, String body, String extra) throws IOException {
        byte[] b = body.getBytes(StandardCharsets.UTF_8);
        String h = "HTTP/1.1 " + status + "\r\n" + (type != null ? "Content-Type: " + type + "\r\n" : "")
                + "Content-Length: " + b.length + "\r\nServer: " + SERVER + "\r\nDate: " + httpDate()
                + "\r\nEXT:\r\nConnection: close\r\n" + (extra != null ? extra : "") + "\r\n";
        out.write(h.getBytes(StandardCharsets.UTF_8));
        out.write(b);
        out.flush();
    }

    private static String envelope(String inner) {
        return "<?xml version=\"1.0\" encoding=\"utf-8\"?><s:Envelope xmlns:s=\"http://schemas.xmlsoap.org/soap/envelope/\" "
                + "s:encodingStyle=\"http://schemas.xmlsoap.org/soap/encoding/\"><s:Body>" + inner + "</s:Body></s:Envelope>";
    }

    private String deviceXml() {
        StringBuilder s = new StringBuilder("<?xml version=\"1.0\" encoding=\"utf-8\"?>"
                + "<root xmlns=\"urn:schemas-upnp-org:device-1-0\" xmlns:dlna=\"urn:schemas-dlna-org:device-1-0\">"
                + "<specVersion><major>1</major><minor>0</minor></specVersion><device>"
                + "<deviceType>" + DEVICE_TYPE + "</deviceType><friendlyName>" + esc(name) + "</friendlyName>"
                + "<manufacturer>bike-dlna</manufacturer><modelName>bike-dlna</modelName><modelNumber>1</modelNumber>"
                + "<modelDescription>DLNA renderer</modelDescription><UDN>uuid:" + uuid + "</UDN>"
                + "<dlna:X_DLNADOC>DMR-1.50</dlna:X_DLNADOC><serviceList>");
        for (String[] sv : new String[][]{{AVT, "AVTransport"}, {RC, "RenderingControl"}, {CM, "ConnectionManager"}})
            s.append("<service><serviceType>").append(sv[0]).append("</serviceType><serviceId>urn:upnp-org:serviceId:")
             .append(sv[1]).append("</serviceId><SCPDURL>/").append(sv[1]).append(".xml</SCPDURL><controlURL>/")
             .append(sv[1]).append("/control</controlURL><eventSubURL>/").append(sv[1]).append("/event</eventSubURL></service>");
        return s.append("</serviceList></device></root>").toString();
    }

    // ---------------------------------------------------------------- SOAP

    static final class UpnpError extends Exception {
        final int code;
        UpnpError(int code, String msg) { super(msg); this.code = code; }
    }

    private String control(String service, String act, String req) throws UpnpError {
        switch (act) {
            // AVTransport
            case "SetAVTransportURI":
                uri = arg(req, "CurrentURI"); meta = arg(req, "CurrentURIMetaData");
                log.log("URI " + uri);
                player.load(uri, arg(meta, "title"));
                return "";
            case "SetNextAVTransportURI": return "";
            case "Play": player.play(); return "";
            case "Pause": player.pause(); return "";
            case "Stop": player.stop(); return "";
            case "Next": case "Previous": return "";
            case "Seek": {
                String unit = arg(req, "Unit"), target = arg(req, "Target");
                if (unit.endsWith("TIME") || unit.isEmpty()) player.seek(parseTime(target));
                else throw new UpnpError(710, "Seek mode not supported: " + unit);
                return "";
            }
            case "GetTransportInfo":
                return out("CurrentTransportState", player.state(), "CurrentTransportStatus", "OK", "CurrentSpeed", "1");
            case "GetPositionInfo": {
                String pos = fmtTime(player.positionMs()), dur = fmtTime(player.durationMs());
                return out("Track", uri.isEmpty() ? "0" : "1", "TrackDuration", dur, "TrackMetaData", meta,
                        "TrackURI", uri, "RelTime", pos, "AbsTime", pos, "RelCount", "2147483647", "AbsCount", "2147483647");
            }
            case "GetMediaInfo":
                return out("NrTracks", uri.isEmpty() ? "0" : "1", "MediaDuration", fmtTime(player.durationMs()),
                        "CurrentURI", uri, "CurrentURIMetaData", meta, "NextURI", "", "NextURIMetaData", "",
                        "PlayMedium", "NETWORK", "RecordMedium", "NOT_IMPLEMENTED", "WriteStatus", "NOT_IMPLEMENTED");
            case "GetDeviceCapabilities":
                return out("PlayMedia", "NETWORK", "RecMedia", "NOT_IMPLEMENTED", "RecQualityModes", "NOT_IMPLEMENTED");
            case "GetTransportSettings": return out("PlayMode", "NORMAL", "RecQualityMode", "NOT_IMPLEMENTED");
            case "GetCurrentTransportActions": return out("Actions", "Play,Pause,Stop,Seek");
            // RenderingControl
            case "GetVolume": return out("CurrentVolume", String.valueOf(player.volume()));
            case "SetVolume": player.setVolume(Integer.parseInt(arg(req, "DesiredVolume").trim())); pool.execute(this::volumeChanged); return "";
            case "GetMute": return out("CurrentMute", "0");
            case "SetMute": return "";
            case "ListPresets": return out("CurrentPresetNameList", "FactoryDefaults");
            case "SelectPreset": return "";
            // ConnectionManager
            case "GetProtocolInfo": return out("Source", "", "Sink", PROTOCOLS);
            case "GetCurrentConnectionIDs": return out("ConnectionIDs", "0");
            case "GetCurrentConnectionInfo":
                return out("RcsID", "0", "AVTransportID", "0", "ProtocolInfo", "", "PeerConnectionManager", "",
                        "PeerConnectionID", "-1", "Direction", "Input", "Status", "OK");
            default: throw new UpnpError(401, "Invalid Action " + act);
        }
    }

    static final String PROTOCOLS = "http-get:*:*:*,http-get:*:video/mp4:*,http-get:*:video/x-flv:*,"
            + "http-get:*:application/vnd.apple.mpegurl:*,http-get:*:application/x-mpegURL:*,http-get:*:audio/mpeg:*,"
            + "http-get:*:audio/mp4:*,http-get:*:video/quicktime:*,http-get:*:video/x-matroska:*";

    private static String out(String... kv) {
        StringBuilder s = new StringBuilder();
        for (int i = 0; i < kv.length; i += 2) s.append('<').append(kv[i]).append('>').append(esc(kv[i + 1])).append("</").append(kv[i]).append('>');
        return s.toString();
    }

    static String arg(String body, String name) {
        Matcher m = Pattern.compile("<(?:\\w+:)?" + name + "(?:\\s[^>]*)?>(.*?)</(?:\\w+:)?" + name + ">", Pattern.DOTALL).matcher(body);
        if (!m.find()) return "";
        String v = m.group(1).trim();
        if (v.startsWith("<![CDATA[") && v.endsWith("]]>")) return v.substring(9, v.length() - 3);
        return unesc(v);
    }

    // ---------------------------------------------------------------- GENA events

    private static final class Sub {
        final String service, url; int seq;
        String last = "";
        Sub(String service, String url) { this.service = service; this.url = url; }
    }

    private String lastChange(String service) {
        String inner;
        if (service.equals(AVT)) {
            String dur = fmtTime(player.durationMs());
            inner = "<Event xmlns=\"urn:schemas-upnp-org:metadata-1-0/AVT/\"><InstanceID val=\"0\">"
                    + "<TransportState val=\"" + player.state() + "\"/><TransportStatus val=\"OK\"/>"
                    + "<CurrentTransportActions val=\"Play,Pause,Stop,Seek\"/>"
                    + "<AVTransportURI val=\"" + esc(uri) + "\"/><CurrentTrackURI val=\"" + esc(uri) + "\"/>"
                    + "<CurrentMediaDuration val=\"" + dur + "\"/><CurrentTrackDuration val=\"" + dur + "\"/>"
                    + "<NumberOfTracks val=\"" + (uri.isEmpty() ? 0 : 1) + "\"/></InstanceID></Event>";
        } else if (service.equals(RC)) {
            inner = "<Event xmlns=\"urn:schemas-upnp-org:metadata-1-0/RCS/\"><InstanceID val=\"0\">"
                    + "<Volume channel=\"Master\" val=\"" + player.volume() + "\"/><Mute channel=\"Master\" val=\"0\"/>"
                    + "</InstanceID></Event>";
        } else {
            return "<e:propertyset xmlns:e=\"urn:schemas-upnp-org:event-1-0\"><e:property><SourceProtocolInfo></SourceProtocolInfo>"
                    + "</e:property><e:property><SinkProtocolInfo>" + PROTOCOLS + "</SinkProtocolInfo></e:property>"
                    + "<e:property><CurrentConnectionIDs>0</CurrentConnectionIDs></e:property></e:propertyset>";
        }
        return "<e:propertyset xmlns:e=\"urn:schemas-upnp-org:event-1-0\"><e:property><LastChange>" + esc(inner)
                + "</LastChange></e:property></e:propertyset>";
    }

    private void sendEvent(String sid, Sub sub) {
        String body = lastChange(sub.service);
        synchronized (sub) {
            if (sub.seq > 0 && body.equals(sub.last)) return;
            sub.last = body;
            try {
                URL u = new URL(sub.url);
                byte[] b = body.getBytes(StandardCharsets.UTF_8);
                try (Socket s = new Socket()) {
                    s.connect(new InetSocketAddress(u.getHost(), u.getPort() < 0 ? 80 : u.getPort()), 2000);
                    s.setSoTimeout(3000);
                    String h = "NOTIFY " + (u.getFile().isEmpty() ? "/" : u.getFile()) + " HTTP/1.1\r\nHOST: " + u.getHost() + ":"
                            + u.getPort() + "\r\nCONTENT-TYPE: text/xml; charset=\"utf-8\"\r\nNT: upnp:event\r\nNTS: upnp:propchange\r\n"
                            + "SID: " + sid + "\r\nSEQ: " + sub.seq++ + "\r\nCONTENT-LENGTH: " + b.length + "\r\nConnection: close\r\n\r\n";
                    OutputStream o = s.getOutputStream();
                    o.write(h.getBytes(StandardCharsets.UTF_8)); o.write(b); o.flush();
                    s.getInputStream().read();
                }
            } catch (Exception e) { log.log("event " + sub.url + ": " + e); subs.remove(sid); }
        }
    }

    /** Pushes AVTransport LastChange whenever the transport state or duration changes. */
    private void eventLoop() {
        String prev = "";
        while (running) {
            String now = player.state() + "|" + uri + "|" + player.durationMs() / 1000;
            if (!now.equals(prev)) {
                prev = now;
                for (Map.Entry<String, Sub> e : subs.entrySet()) if (e.getValue().service.equals(AVT)) sendEvent(e.getKey(), e.getValue());
            }
            try { Thread.sleep(200); } catch (InterruptedException e) { return; }
        }
    }

    private void volumeChanged() {
        for (Map.Entry<String, Sub> e : subs.entrySet()) if (e.getValue().service.equals(RC)) sendEvent(e.getKey(), e.getValue());
    }

    // ---------------------------------------------------------------- helpers

    static String header(String msg, String name) {
        for (String l : msg.split("\r\n")) {
            int i = l.indexOf(':');
            if (i > 0 && l.substring(0, i).trim().equalsIgnoreCase(name)) return l.substring(i + 1).trim();
        }
        return null;
    }

    static String esc(String s) {
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;").replace("'", "&apos;");
    }

    static String unesc(String s) {
        Matcher m = Pattern.compile("&#(x?)([0-9a-fA-F]+);").matcher(s);
        StringBuffer b = new StringBuffer();
        while (m.find()) m.appendReplacement(b, Matcher.quoteReplacement(
                String.valueOf((char) Integer.parseInt(m.group(2), m.group(1).isEmpty() ? 10 : 16))));
        m.appendTail(b);
        return b.toString().replace("&lt;", "<").replace("&gt;", ">").replace("&quot;", "\"").replace("&apos;", "'").replace("&amp;", "&");
    }

    static String fmtTime(long ms) {
        long s = Math.max(0, ms) / 1000;
        return String.format(Locale.US, "%d:%02d:%02d", s / 3600, s / 60 % 60, s % 60);
    }

    static long parseTime(String t) {
        t = t.trim();
        if (!t.contains(":")) return (long) (Double.parseDouble(t) * 1000);
        String[] p = t.split(":");
        double sec = 0;
        for (String x : p) sec = sec * 60 + Double.parseDouble(x);
        return (long) (sec * 1000);
    }

    private static String httpDate() {
        SimpleDateFormat f = new SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss 'GMT'", Locale.US);
        f.setTimeZone(TimeZone.getTimeZone("GMT"));
        return f.format(new Date());
    }
}
