// The finishing pass: removes banding, sharpens and, for line art, steepens edges, then dithers. One read of the picture
// and its eight neighbours; each stage is skipped when its strength is 0.
//   uSharpen          0...1, contrast-adaptive: the boost is held back where a pixel is near black or white, since the
//                     headroom to those limits (relative to the local peak) is what a boost may use.
//   uSteepen          0...1, line art: a pixel on the shoulder of a soft edge moves toward the darkest or brightest
//                     neighbour, whichever side of the edge it is on, so the edge narrows. Flat areas are left alone.
//   uDebandThreshold  largest difference from the average of a ring of samples that is still smoothed, as a fraction of
//                     full scale; 0 turns the stage off.
//   uDebandRadius     reach of the ring in pixels.
//   uFrame            changes with each frame, so the noise does not sit still.
#ifdef GL_FRAGMENT_PRECISION_HIGH
precision highp float;
#else
precision mediump float;
#endif

uniform sampler2D uTexSampler;
uniform vec2 uSize;
uniform float uSharpen;
uniform float uSteepen;
uniform float uDebandThreshold;
uniform float uDebandRadius;
uniform float uFrame;
varying vec2 vTexCoord;

vec3 fetch(vec2 p) {
	return texture2D(uTexSampler, p).rgb;
}

float luma(vec3 c) {
	return dot(c, vec3(0.2126, 0.7152, 0.0722));
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

void main() {
	vec2 px = 1.0 / uSize;
	vec2 seed = gl_FragCoord.xy + uFrame * 5.588238;
	vec3 raw = fetch(vTexCoord);
	vec3 c = raw;

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
		vec3 apart = abs(average - raw);
		// The largest channel decides for all three, so a faint colour edge is not smoothed in one channel only.
		float largest = max(max(apart.r, apart.g), apart.b);
		c = mix(raw, average, 1.0 - smoothstep(uDebandThreshold, 2.0 * uDebandThreshold, largest));
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
		vec3 delta = vec3(0.0);

		if (uSharpen > 0.0) {
			vec3 lo = min(min(min(n, s), min(w, e)), raw);
			vec3 hi = max(max(max(n, s), max(w, e)), raw);
			lo = min(lo, min(min(nw, ne), min(sw, se)));
			hi = max(hi, max(max(nw, ne), max(sw, se)));
			vec3 room = sqrt(clamp(min(lo, 1.0 - hi) / max(hi, 0.001), 0.0, 1.0));
			vec3 weight = -(0.02 + 0.18 * uSharpen) * room;
			delta += weight * (n + s + w + e - 4.0 * raw) / (1.0 + 4.0 * weight);
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
			delta += ((bend > 0.0 ? dark : bright) - raw) * (uSteepen * edge);
		}
		c += delta;
	}

	if (uDebandThreshold > 0.0) {
		// Triangular noise of one step either way breaks up the contours the smoothing leaves in an 8-bit picture. True
		// black is left as it is.
		float step8 = (noise(seed + 31.0) + noise(seed + 53.0) - 1.0) / 255.0;
		c += step8 * step(2.0 / 255.0, max(max(c.r, c.g), c.b));
	}
	gl_FragColor = vec4(clamp(c, 0.0, 1.0), 1.0);
}
