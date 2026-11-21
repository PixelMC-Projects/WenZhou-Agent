#version 150
#moj_import <fog.glsl>

in vec3 Position;
in vec4 Color;
in vec2 UV0;
uniform mat4 ModelViewMat;
uniform mat4 ProjMat;
uniform float LineWidth;
uniform vec2 ScreenSize;
uniform int FogShape;
out float vertexDistance;
out vec4 vertexColor;

void main() {
    vec4 view = ModelViewMat * vec4(Position, 1.0);
    view.xyz *= 1.0 - 1.0 / 256.0;
    vec4 clip = ProjMat * view;
    float side = (gl_VertexID % 2 == 0) ? 1.0 : -1.0;
    clip.xy += side * UV0 * (LineWidth / ScreenSize) * clip.w;
    gl_Position = clip;
    vertexDistance = fog_distance(ModelViewMat, Position, FogShape);
    vertexColor = Color;
}
