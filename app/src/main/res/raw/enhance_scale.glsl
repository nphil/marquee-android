// The upscale pass: makes the picture the size of the display with a separable four-tap kernel.
//   LANCZOS: textbook Lanczos with two lobes, 12 texture reads a pixel, and the result is held to the range of the four
//            nearest source pixels so the kernel's negative lobes cannot ring around an edge.
//   BICUBIC: Catmull-Rom, 9 texture reads, no clamp. The cheaper step down when the GPU cannot keep up.
// The input has to be sampled with linear filtering: two neighbours whose weights have the same sign are read as one
// bilinear fetch placed at their weighted centre, which is exact and halves the reads of the kernel's outer taps.
#ifdef GL_FRAGMENT_PRECISION_HIGH
precision highp float;
#else
precision mediump float;
#endif

uniform sampler2D uTexSampler;
uniform vec2 uSourceSize;
varying vec2 vTexCoord;

const float PI = 3.14159265;

// The weights of the four source pixels at -1, 0, 1 and 2 around a point that lies t past the second of them.
vec4 kernel(float t) {
#ifdef LANCZOS
	// sinc(x) * sinc(x / 2) at the four distances, written with the half-angle sines of t so that only two are needed.
	t = clamp(t, 1e-4, 1.0 - 1e-4);
	float a = sin(0.5 * PI * t);
	float b = cos(0.5 * PI * t);
	float k = 4.0 * a * b / (PI * PI);
	vec4 w = vec4(
		-k * b / ((1.0 + t) * (1.0 + t)),
		k * a / (t * t),
		k * b / ((1.0 - t) * (1.0 - t)),
		-k * a / ((2.0 - t) * (2.0 - t)));
#else
	float t2 = t * t;
	float t3 = t2 * t;
	vec4 w = vec4(
		-0.5 * t3 + t2 - 0.5 * t,
		1.5 * t3 - 2.5 * t2 + 1.0,
		-1.5 * t3 + 2.0 * t2 + 0.5 * t,
		0.5 * t3 - 0.5 * t2);
#endif
	return w / (w.x + w.y + w.z + w.w);
}

vec3 fetch(vec2 p) {
	return texture2D(uTexSampler, p).rgb;
}

void main() {
	vec2 inv = 1.0 / uSourceSize;
	vec2 pos = vTexCoord * uSourceSize - 0.5;
	vec2 origin = floor(pos);
	vec2 base = origin + 0.5;
	vec4 wx = kernel(pos.x - origin.x);
	vec4 wy = kernel(pos.y - origin.y);

	// The middle two taps of each axis share a sign, so they are one fetch: at their weighted centre, weighted as one.
	float sx = wx.y + wx.z;
	float sy = wy.y + wy.z;
	float mx = base.x + wx.z / sx;
	float my = base.y + wy.z / sy;
	vec2 lo = (base - 1.0) * inv;
	vec2 hi = (base + 2.0) * inv;

	vec3 sum =
		fetch(vec2(lo.x, lo.y)) * (wx.x * wy.x) +
		fetch(vec2(hi.x, lo.y)) * (wx.w * wy.x) +
		fetch(vec2(lo.x, hi.y)) * (wx.x * wy.w) +
		fetch(vec2(hi.x, hi.y)) * (wx.w * wy.w) +
		fetch(vec2(mx * inv.x, lo.y)) * (sx * wy.x) +
		fetch(vec2(mx * inv.x, hi.y)) * (sx * wy.w) +
		fetch(vec2(lo.x, my * inv.y)) * (wx.x * sy) +
		fetch(vec2(hi.x, my * inv.y)) * (wx.w * sy);

#ifdef LANCZOS
	vec3 c00 = fetch(base * inv);
	vec3 c10 = fetch(vec2(base.x + 1.0, base.y) * inv);
	vec3 c01 = fetch(vec2(base.x, base.y + 1.0) * inv);
	vec3 c11 = fetch((base + 1.0) * inv);
	sum += c00 * (wx.y * wy.y) + c10 * (wx.z * wy.y) + c01 * (wx.y * wy.z) + c11 * (wx.z * wy.z);
	sum = clamp(sum, min(min(c00, c10), min(c01, c11)), max(max(c00, c10), max(c01, c11)));
#else
	sum += fetch(vec2(mx, my) * inv) * (sx * sy);
#endif

	gl_FragColor = vec4(sum, 1.0);
}
