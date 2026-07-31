#version 450

layout(location = 0) in vec4 inRect;
layout(location = 1) in uvec4 inUvPixels;
layout(location = 2) in vec4 inColor;
layout(location = 3) in uint inMode;

layout(location = 0) out vec2 outUv;
layout(location = 1) out vec4 outColor;
layout(location = 2) flat out uint outMode;

layout(push_constant) uniform RenderPush {
    vec4 viewportAndYOffset;
    vec4 atlasSizes;
} renderPush;

void main() {
    const vec2 corners[6] = vec2[6](
        vec2(0.0, 0.0), vec2(0.0, 1.0), vec2(1.0, 1.0),
        vec2(0.0, 0.0), vec2(1.0, 1.0), vec2(1.0, 0.0));
    vec2 corner = corners[gl_VertexIndex % 6];
    vec2 position = mix(inRect.xy, inRect.zw, corner);
    vec2 safeViewport = max(renderPush.viewportAndYOffset.xy, vec2(1.0));
    vec2 translated = vec2(position.x,
                           position.y + renderPush.viewportAndYOffset.z);
    vec2 normalized = translated / safeViewport;
    // Vulkan's positive-height viewport maps NDC -1 to framebuffer row 0.
    // Terminal geometry already uses Android's top-left, Y-down coordinates.
    gl_Position = vec4(normalized.x * 2.0 - 1.0,
                       normalized.y * 2.0 - 1.0,
                       0.0, 1.0);
    vec2 uvPixels = mix(vec2(inUvPixels.xy), vec2(inUvPixels.zw), corner);
    vec2 atlasSize = inMode == 2u
        ? vec2(renderPush.atlasSizes.y)
        : (inMode == 3u ? renderPush.atlasSizes.zw
                        : vec2(renderPush.atlasSizes.x));
    outUv = uvPixels / max(atlasSize, vec2(1.0));
    outColor = inColor;
    outMode = inMode;
}
