// Draws one quad over the whole target and hands the fragment shader the picture's coordinates.
// The sources are loaded with a "#version 100" line and their #defines in front (EnhanceEffects.kt).
attribute vec4 aFramePosition;
varying vec2 vTexCoord;

void main() {
	gl_Position = aFramePosition;
	vTexCoord = aFramePosition.xy * 0.5 + 0.5;
}
