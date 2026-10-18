"""Cut Noto Sans SC down to what video titles need: GB2312 (the 6,763 common simplified hanzi), ASCII, CJK punctuation.

  pip install fonttools
  curl -LO https://github.com/notofonts/noto-cjk/raw/main/Sans/SubsetOTF/SC/NotoSansSC-Regular.otf
  python3 tools/subset-font.py NotoSansSC-Regular.otf    # → app/src/main/assets/title.otf
"""
import sys
from fontTools import subset

chars = {chr(c) for c in range(0x20, 0x7F)}
for hi in range(0xA1, 0xF8):
    for lo in range(0xA1, 0xFF):
        try: chars.add(bytes([hi, lo]).decode('gb2312'))
        except UnicodeDecodeError: pass
chars |= {chr(c) for c in [*range(0x3000, 0x3040), *range(0xFF00, 0xFFF0), *range(0x2010, 0x2070)]}

opts = subset.Options()
opts.layout_features = ['*']
opts.name_IDs = ['*']
opts.notdef_outline = True
font = subset.load_font(sys.argv[1], opts)
s = subset.Subsetter(opts)
s.populate(text=''.join(chars))
s.subset(font)
subset.save_font(font, 'app/src/main/assets/title.otf', opts)
print(len(chars), 'characters')
