// The finishing pass: smooths block seams and noise, removes banding, sharpens and, for line art, steepens edges, lifts
// local contrast and colour, then dithers. Each stage is skipped when its strength is 0, so one pass serves every mix.
// The order is the order of the picture's faults: what compression put in (seams, noise, bands) before what is made
// of the detail left (sharpen, steepen, vivid).
//   uSourceSize       the picture's size before any upscale: Deblock follows its 8x8 blocks, and the filters that look
//                     around a pixel step by one of its pixels.
//   uDeblock          largest step across a block boundary, as a fraction of full scale, still taken for a seam; 0 is off.
//   uNoiseSigma       how far a neighbour may differ and still be averaged in (distance in RGB); 0 is off.
//   uNoiseBlend       how much of the filtered pixel replaces the original.
//   uDebandThreshold  largest difference from the average of a ring of samples that is still smoothed, as a fraction of
//                     full scale; 0 turns the stage off.
//   uDebandRadius     reach of the ring in pixels.
//   uSharpen          the weight of the sharpen, 0 for off. The result is held to the range of the pixel's neighbours, so
//                     a strong weight cannot ring: that is what lets the slider go twice as far as it did.
//   uSteepen          0...1, line art: a pixel on the shoulder of a soft edge moves toward the darkest or brightest
//                     neighbour, whichever side of the edge it is on, so the edge narrows. Flat areas are left alone.
//   uVivid            0...1, local contrast (the pixel against the average of a ring of three source pixels, held back
//                     near black and white) and a colour lift that favours dull colours; 0 is off.
//   uDither           1 where the stages above leave smooth gradients that need noise to hide their steps.
//   uSplit            1 while a slider is dragged: the left half of the picture is passed through as it came.
//   uFrame            changes with each frame, so the noise does not sit still.
#ifdef GL_FRAGMENT_PRECISION_HIGH
precision highp float;
#else
precision mediump float;
#endif

uniform sampler2D uTexSampler;
uniform vec2 uSize;
uniform vec2 uSourceSize;
uniform float uDeblock;
uniform float uNoiseSigma;
uniform float uNoiseBlend;
uniform float uDebandThreshold;
uniform float uDebandRadius;
uniform float uSharpen;
uniform float uSteepen;
uniform float uVivid;
uniform float uDither;
uniform float uSplit;
uniform float uFrame;
varying vec2 vTexCoord;

vec3 fetch(vec2 p) {
	return texture2D(uTexSampler, p).rgb;
}

float luma(vec3 c) {
	return dot(c, vec3(0.2126, 0.7152, 0.0722));
}

float largest(vec3 v) {
	return max(max(v.r, v.g), v.b);
}

// Uniform noise in 0...1 from a pixel position: the interleaved gradient noise of Jimenez (2014), which has no
// repeating pattern a viewer can pick out and needs no table or integer maths.
float noise(vec2 p) {
	return fract(52.9829189 * fract(dot(p, vec2(0.06711056, 0.00583715))));
}

void extremes(vec3 v, inout vec3 dark, inout float darkest, inout vec3 bright, inout float brightest) {
	float l = luma(v);
	if (l < darkest) {
		dark = v;
		darkest = l;
	}
	if (l > brightest) {
		bright = v;
		brightest = l;
	}
}

// One neighbour of the noise filter: its weight falls off with its distance in colour from the centre, so an edge, whose
// far side is a long way off, is not blurred across.
void tap(vec2 offset, vec3 centre, float invSigma2, inout vec3 sum, inout float total) {
	vec3 v = fetch(vTexCoord + offset);
	vec3 d = v - centre;
	float w = 1.0 / (1.0 + dot(d, d) * invSigma2);
	w *= w;
	sum += v * w;
	total += w;
}

// The shift of a pixel that lies within two source pixels of a block boundary on one axis: where the two pixels
// either side of the line differ by less than the threshold the step is a seam, not detail, and the pixels near it are
// moved half the step each way, so the two sides meet. [at] is the position in source pixels along the axis, [other]
// the position on the other axis, [vertical] says which axis it is.
vec3 seam(float at, float other, float extent, bool vertical, vec2 inv) {
	float line = 8.0 * floor(at / 8.0 + 0.5);
	float d = at - line;
	if (abs(d) >= 2.0 || line < 0.5 || line > extent - 0.5) return vec3(0.0);
	vec2 before = vertical ? vec2(line - 0.5, other) : vec2(other, line - 0.5);
	vec2 after = vertical ? vec2(line + 0.5, other) : vec2(other, line + 0.5);
	vec3 jump = fetch(after * inv) - fetch(before * inv);
	float seamLike = 1.0 - smoothstep(uDeblock, 2.0 * uDeblock, largest(abs(jump)));
	float reach = 1.0 - 0.5 * abs(d);
	return jump * (d < 0.0 ? 0.5 : -0.5) * (reach * seamLike);
}

void main() {
	vec3 raw = fetch(vTexCoord);
	if (uSplit > 0.5 && vTexCoord.x < 0.5) {
		gl_FragColor = vec4(raw, 1.0);
		return;
	}
	vec2 px = 1.0 / uSize;
	vec2 sp = 1.0 / uSourceSize;
	vec2 seed = gl_FragCoord.xy + uFrame * 5.588238;
	vec3 c = raw;

	if (uDeblock > 0.0) {
		vec2 at = vTexCoord * uSourceSize;
		c += seam(at.x, at.y, uSourceSize.x, true, sp) + seam(at.y, at.x, uSourceSize.y, false, sp);
	}

	if (uNoiseSigma > 0.0) {
		float inv = 1.0 / (uNoiseSigma * uNoiseSigma);
		vec3 sum = c;
		float total = 1.0;
		tap(vec2(-sp.x, -sp.y), c, inv, sum, total);
		tap(vec2(0.0, -sp.y), c, inv, sum, total);
		tap(vec2(sp.x, -sp.y), c, inv, sum, total);
		tap(vec2(-sp.x, 0.0), c, inv, sum, total);
		tap(vec2(sp.x, 0.0), c, inv, sum, total);
		tap(vec2(-sp.x, sp.y), c, inv, sum, total);
		tap(vec2(0.0, sp.y), c, inv, sum, total);
		tap(vec2(sp.x, sp.y), c, inv, sum, total);
		c = mix(c, sum / total, uNoiseBlend);
	}

	if (uDebandThreshold > 0.0) {
		// Four samples on a ring turned and sized at random for each pixel: where they agree with the pixel to within
		// the threshold the pixel is on a slow gradient and takes their average; where they do not it is an edge or detail.
		float angle = 6.2831853 * noise(seed);
		float reach = uDebandRadius * (0.5 + 0.5 * noise(seed + 17.0));
		vec2 a = vec2(cos(angle), sin(angle)) * reach;
		vec2 b = vec2(-a.y, a.x);
		vec3 average = 0.25 * (
			fetch(vTexCoord + a * px) + fetch(vTexCoord - a * px) +
			fetch(vTexCoord + b * px) + fetch(vTexCoord - b * px));
		// The largest channel decides for all three, so a faint colour edge is not smoothed in one channel only.
		float apart = largest(abs(average - c));
		c = mix(c, average, 1.0 - smoothstep(uDebandThreshold, 2.0 * uDebandThreshold, apart));
	}

	if (uSharpen > 0.0 || uSteepen > 0.0) {
		vec3 n = fetch(vTexCoord + vec2(0.0, -px.y));
		vec3 s = fetch(vTexCoord + vec2(0.0, px.y));
		vec3 w = fetch(vTexCoord + vec2(-px.x, 0.0));
		vec3 e = fetch(vTexCoord + vec2(px.x, 0.0));
		vec3 nw = fetch(vTexCoord + vec2(-px.x, -px.y));
		vec3 ne = fetch(vTexCoord + vec2(px.x, -px.y));
		vec3 sw = fetch(vTexCoord + vec2(-px.x, px.y));
		vec3 se = fetch(vTexCoord + vec2(px.x, px.y));

		if (uSharpen > 0.0) {
			vec3 lo = min(min(min(n, s), min(w, e)), raw);
			vec3 hi = max(max(max(n, s), max(w, e)), raw);
			lo = min(lo, min(min(nw, ne), min(sw, se)));
			hi = max(hi, max(max(nw, ne), max(sw, se)));
			// The boost is held back where a pixel is near black or white: the headroom to those limits, relative to the
			// local peak, is what a boost may use.
			vec3 room = sqrt(clamp(min(lo, 1.0 - hi) / max(hi, 0.001), 0.0, 1.0));
			vec3 weight = -uSharpen * room;
			vec3 sharp = c + weight * (n + s + w + e - 4.0 * raw) / (1.0 + 4.0 * weight);
			// Held to the neighbours' range (or where the pixel already was, if the stages before moved it outside).
			c = clamp(sharp, min(lo, c), max(hi, c));
		}

		if (uSteepen > 0.0) {
			float here = luma(raw);
			vec3 dark = raw;
			vec3 bright = raw;
			float darkest = here;
			float brightest = here;
			extremes(n, dark, darkest, bright, brightest);
			extremes(s, dark, darkest, bright, brightest);
			extremes(w, dark, darkest, bright, brightest);
			extremes(e, dark, darkest, bright, brightest);
			extremes(nw, dark, darkest, bright, brightest);
			extremes(ne, dark, darkest, bright, brightest);
			extremes(sw, dark, darkest, bright, brightest);
			extremes(se, dark, darkest, bright, brightest);
			float range = brightest - darkest;
			// Positive on the dark side of an edge's bend, negative on the bright side, zero on a straight ramp.
			float bend = 0.25 * (luma(n) + luma(s) + luma(w) + luma(e)) - here;
			float edge = smoothstep(0.04, 0.2, range) * clamp(abs(bend) * 2.0 / max(range, 0.001), 0.0, 1.0);
			c += ((bend > 0.0 ? dark : bright) - raw) * (uSteepen * edge);
		}
	}

	if (uVivid > 0.0) {
		vec2 r = 3.0 * sp;
		float y = luma(c);
		float around = 0.2 * (y +
			luma(fetch(vTexCoord + vec2(-r.x, -r.y))) + luma(fetch(vTexCoord + vec2(r.x, -r.y))) +
			luma(fetch(vTexCoord + vec2(-r.x, r.y))) + luma(fetch(vTexCoord + vec2(r.x, r.y))));
		// Midtones take the contrast; black and white are left where they are.
		float room = clamp(4.0 * y * (1.0 - y), 0.0, 1.0);
		c += (0.5 * uVivid * room * (y - around));
		// Dull colours gain more than saturated ones, so skin and sky do not run to neon.
		float chroma = largest(c) - min(min(c.r, c.g), c.b);
		c = mix(vec3(luma(c)), c, 1.0 + 0.35 * uVivid * (1.0 - clamp(chroma, 0.0, 1.0)));
	}

	if (uDither > 0.5) {
		// Triangular noise of one step either way breaks up the contours the smoothing leaves in an 8-bit picture. True
		// black is left as it is.
		float step8 = (noise(seed + 31.0) + noise(seed + 53.0) - 1.0) / 255.0;
		c += step8 * step(2.0 / 255.0, largest(c));
	}
	gl_FragColor = vec4(clamp(c, 0.0, 1.0), 1.0);
}
