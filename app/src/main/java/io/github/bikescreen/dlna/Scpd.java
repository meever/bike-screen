package io.github.bikescreen.dlna;

/**
 * Service descriptions (SCPD) built from a compact spec.
 * Action: "Name in:Var ... > out:Var ..."  — argument name, then its related state variable.
 * Variable: "Name" (string), "Name:type", or "Name:type:yes" (sends events).
 */
final class Scpd {
    private Scpd() {}

    static String avTransport() {
        return build(new String[]{
                "SetAVTransportURI InstanceID:A_ARG_TYPE_InstanceID CurrentURI:AVTransportURI CurrentURIMetaData:AVTransportURIMetaData",
                "SetNextAVTransportURI InstanceID:A_ARG_TYPE_InstanceID NextURI:NextAVTransportURI NextURIMetaData:NextAVTransportURIMetaData",
                "GetMediaInfo InstanceID:A_ARG_TYPE_InstanceID > NrTracks:NumberOfTracks MediaDuration:CurrentMediaDuration CurrentURI:AVTransportURI CurrentURIMetaData:AVTransportURIMetaData NextURI:NextAVTransportURI NextURIMetaData:NextAVTransportURIMetaData PlayMedium:PlaybackStorageMedium RecordMedium:RecordStorageMedium WriteStatus:RecordMediumWriteStatus",
                "GetTransportInfo InstanceID:A_ARG_TYPE_InstanceID > CurrentTransportState:TransportState CurrentTransportStatus:TransportStatus CurrentSpeed:TransportPlaySpeed",
                "GetPositionInfo InstanceID:A_ARG_TYPE_InstanceID > Track:CurrentTrack TrackDuration:CurrentTrackDuration TrackMetaData:CurrentTrackMetaData TrackURI:CurrentTrackURI RelTime:RelativeTimePosition AbsTime:AbsoluteTimePosition RelCount:RelativeCounterPosition AbsCount:AbsoluteCounterPosition",
                "GetDeviceCapabilities InstanceID:A_ARG_TYPE_InstanceID > PlayMedia:PossiblePlaybackStorageMedia RecMedia:PossibleRecordStorageMedia RecQualityModes:PossibleRecordQualityModes",
                "GetTransportSettings InstanceID:A_ARG_TYPE_InstanceID > PlayMode:CurrentPlayMode RecQualityMode:CurrentRecordQualityMode",
                "Stop InstanceID:A_ARG_TYPE_InstanceID",
                "Play InstanceID:A_ARG_TYPE_InstanceID Speed:TransportPlaySpeed",
                "Pause InstanceID:A_ARG_TYPE_InstanceID",
                "Seek InstanceID:A_ARG_TYPE_InstanceID Unit:A_ARG_TYPE_SeekMode Target:A_ARG_TYPE_SeekTarget",
                "Next InstanceID:A_ARG_TYPE_InstanceID",
                "Previous InstanceID:A_ARG_TYPE_InstanceID",
                "GetCurrentTransportActions InstanceID:A_ARG_TYPE_InstanceID > Actions:CurrentTransportActions",
        }, new String[]{
                "TransportState", "TransportStatus", "PlaybackStorageMedium", "RecordStorageMedium",
                "PossiblePlaybackStorageMedia", "PossibleRecordStorageMedia", "CurrentPlayMode", "TransportPlaySpeed",
                "RecordMediumWriteStatus", "CurrentRecordQualityMode", "PossibleRecordQualityModes",
                "NumberOfTracks:ui4", "CurrentTrack:ui4", "CurrentTrackDuration", "CurrentMediaDuration",
                "CurrentTrackMetaData", "CurrentTrackURI", "AVTransportURI", "AVTransportURIMetaData",
                "NextAVTransportURI", "NextAVTransportURIMetaData", "RelativeTimePosition", "AbsoluteTimePosition",
                "RelativeCounterPosition:i4", "AbsoluteCounterPosition:i4", "CurrentTransportActions",
                "LastChange:string:yes", "A_ARG_TYPE_SeekMode", "A_ARG_TYPE_SeekTarget", "A_ARG_TYPE_InstanceID:ui4",
        });
    }

    static String renderingControl() {
        return build(new String[]{
                "ListPresets InstanceID:A_ARG_TYPE_InstanceID > CurrentPresetNameList:PresetNameList",
                "SelectPreset InstanceID:A_ARG_TYPE_InstanceID PresetName:A_ARG_TYPE_PresetName",
                "GetMute InstanceID:A_ARG_TYPE_InstanceID Channel:A_ARG_TYPE_Channel > CurrentMute:Mute",
                "SetMute InstanceID:A_ARG_TYPE_InstanceID Channel:A_ARG_TYPE_Channel DesiredMute:Mute",
                "GetVolume InstanceID:A_ARG_TYPE_InstanceID Channel:A_ARG_TYPE_Channel > CurrentVolume:Volume",
                "SetVolume InstanceID:A_ARG_TYPE_InstanceID Channel:A_ARG_TYPE_Channel DesiredVolume:Volume",
        }, new String[]{
                "PresetNameList", "LastChange:string:yes", "Mute:boolean", "Volume:ui2",
                "A_ARG_TYPE_Channel", "A_ARG_TYPE_InstanceID:ui4", "A_ARG_TYPE_PresetName",
        });
    }

    static String connectionManager() {
        return build(new String[]{
                "GetProtocolInfo > Source:SourceProtocolInfo Sink:SinkProtocolInfo",
                "GetCurrentConnectionIDs > ConnectionIDs:CurrentConnectionIDs",
                "GetCurrentConnectionInfo ConnectionID:A_ARG_TYPE_ConnectionID > RcsID:A_ARG_TYPE_RcsID AVTransportID:A_ARG_TYPE_AVTransportID ProtocolInfo:A_ARG_TYPE_ProtocolInfo PeerConnectionManager:A_ARG_TYPE_ConnectionManager PeerConnectionID:A_ARG_TYPE_ConnectionID Direction:A_ARG_TYPE_Direction Status:A_ARG_TYPE_ConnectionStatus",
        }, new String[]{
                "SourceProtocolInfo:string:yes", "SinkProtocolInfo:string:yes", "CurrentConnectionIDs:string:yes",
                "A_ARG_TYPE_ConnectionStatus", "A_ARG_TYPE_ConnectionManager", "A_ARG_TYPE_Direction",
                "A_ARG_TYPE_ProtocolInfo", "A_ARG_TYPE_ConnectionID:i4", "A_ARG_TYPE_AVTransportID:i4", "A_ARG_TYPE_RcsID:i4",
        });
    }

    private static String build(String[] actions, String[] vars) {
        StringBuilder s = new StringBuilder("<?xml version=\"1.0\" encoding=\"utf-8\"?><scpd xmlns=\"urn:schemas-upnp-org:service-1-0\">"
                + "<specVersion><major>1</major><minor>0</minor></specVersion><actionList>");
        for (String a : actions) {
            String[] tok = a.split(" ");
            s.append("<action><name>").append(tok[0]).append("</name><argumentList>");
            String dir = "in";
            for (int i = 1; i < tok.length; i++) {
                if (tok[i].equals(">")) { dir = "out"; continue; }
                String[] nv = tok[i].split(":");
                s.append("<argument><name>").append(nv[0]).append("</name><direction>").append(dir)
                 .append("</direction><relatedStateVariable>").append(nv[1]).append("</relatedStateVariable></argument>");
            }
            s.append("</argumentList></action>");
        }
        s.append("</actionList><serviceStateTable>");
        for (String v : vars) {
            String[] p = v.split(":");
            s.append("<stateVariable sendEvents=\"").append(p.length > 2 ? "yes" : "no").append("\"><name>").append(p[0])
             .append("</name><dataType>").append(p.length > 1 ? p[1] : "string").append("</dataType>");
            if (p[0].equals("Volume")) s.append("<allowedValueRange><minimum>0</minimum><maximum>100</maximum><step>1</step></allowedValueRange>");
            s.append("</stateVariable>");
        }
        return s.append("</serviceStateTable></scpd>").toString();
    }
}
