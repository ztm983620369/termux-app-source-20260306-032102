#version 450

layout(set = 0, binding = 0) uniform sampler2D maskTexture;
layout(set = 0, binding = 1) uniform sampler2D colorTexture;
layout(set = 0, binding = 2) uniform sampler2D runMaskTexture;

layout(location = 0) in vec2 inUv;
layout(location = 1) in vec4 inColor;
layout(location = 2) flat in uint inMode;

layout(location = 0) out vec4 outColor;

void main() {
    if (inMode == 0u) {
        outColor = vec4(inColor.rgb * inColor.a, inColor.a);
    } else if (inMode == 1u) {
        float alpha = texture(maskTexture, inUv).r * inColor.a;
        outColor = vec4(inColor.rgb * alpha, alpha);
    } else if (inMode == 2u) {
        vec4 glyph = texture(colorTexture, inUv);
        float alpha = glyph.a * inColor.a;
        outColor = vec4(glyph.rgb * alpha, alpha);
    } else {
        float alpha = texture(runMaskTexture, inUv).r * inColor.a;
        outColor = vec4(inColor.rgb * alpha, alpha);
    }
}
