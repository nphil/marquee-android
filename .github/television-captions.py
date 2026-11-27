"""Compare the four decoded cue types in production TV caption views, using emulator pixels."""
import io
import json
from pathlib import Path
import subprocess
import sys
import time

from PIL import Image, ImageChops


def shell(*args):
    return subprocess.check_output(['adb', 'shell', *args], text=True).strip()


def bounds(image):
    channels = image.convert('RGB').split()
    high = [ch.point(lambda c: 255 if c > 170 else 0) for ch in channels]
    low = [ch.point(lambda c: 255 if c < 90 else 0) for ch in channels]
    def mask(r, g, b):
        return ImageChops.darker(ImageChops.darker(r, g), b).getbbox()
    return dict(dialogue=mask(*high), sign=mask(low[0], high[1], low[2]),
                exit_sign=mask(high[0], high[1], low[2]),
                bitmap=mask(low[0], high[1], high[2]), vertical=mask(high[0], low[1], high[2]))


def capture(out, pkg, vertical, top, hud):
    name = f"caption-{'vertical' if vertical else 'canvas'}-{'top' if top else 'bottom'}-{'hud' if hud else 'clear'}"
    shell('am', 'start', '-W', '-S', '-n', f'{pkg}/com.nphil.marquee.television.CaptionReviewActivity',
          '--ez', 'top', str(top).lower(), '--ez', 'hud', str(hud).lower(), '--ez', 'vertical', str(vertical).lower())
    previous = None
    deadline = time.monotonic() + 25
    while time.monotonic() < deadline:
        png = subprocess.check_output(['adb', 'exec-out', 'screencap', '-p'])
        with Image.open(io.BytesIO(png)) as image:
            box, size = bounds(image), image.size
        if all(box[k] for k in ('dialogue', 'sign', 'exit_sign', 'bitmap')) and bool(box['vertical']) == vertical and box == previous:
            break
        previous = box
        time.sleep(.2)
    else:
        (out / (name + '.png')).write_bytes(png)
        raise AssertionError(f'{name}: did not settle: {previous}')
    (out / (name + '.png')).write_bytes(png)
    print(name, box, flush=True)
    return box, size


def review(out, pkg):
    if pkg != 'com.nphil.marquee.tv.review' or shell('getprop', 'ro.kernel.qemu') != '1':
        raise RuntimeError('Offline TV review emulator required')
    measurements = {}
    for vertical in (False, True):
        states = {}
        for top in (False, True):
            for hud in (False, True):
                box, (w, h) = capture(out, pkg, vertical, top, hud)
                states[top, hud] = box
                measurements[f'{vertical},{top},{hud}'] = box
        (out / 'caption-bounds.json').write_text(json.dumps(measurements, indent=2))
        base = states[False, False]
        for box in states.values():
            for key in ('bitmap', 'sign', 'exit_sign', 'vertical') if vertical else ('bitmap', 'sign', 'exit_sign'):
                assert max(abs(a-b) for a,b in zip(box[key], base[key])) <= 2, (key, base, box)
        assert abs(base['bitmap'][0]/w - .12) < .015 and abs(base['bitmap'][1]/h - .22) < .015, base
        assert base['sign'][0] < .25*w and base['sign'][1] > .65*h, base
        assert base['exit_sign'][2] > .85*w and base['exit_sign'][3] < .25*h, base
        if vertical:
            l,t,r,b = base['vertical']
            assert b-t > 2*(r-l), ('not vertical writing', base)
        assert base['dialogue'][1] > .65*h, base
        assert states[False, True]['dialogue'][1] < base['dialogue'][1] - .02*h, states
        assert states[True, False]['dialogue'][1] < .25*h, states
        assert abs(states[True, True]['dialogue'][1] - states[True, False]['dialogue'][1]) <= 2, states


if __name__ == '__main__':
    review(Path(sys.argv[1]), sys.argv[2])
