package app.orcaandroid.render

/** GLSL ES 3.0 programs of [PlateRenderer]. Attribute locations are fixed with layout qualifiers. */
internal object Shaders {

    /** Scene mesh: position, normal, (object index, volume type; 100 + filament for parts). */
    const val MESH_VS = """#version 300 es
        uniform mat4 uMvp;
        uniform float uSelected;
        uniform vec3 uDrag;
        layout(location = 0) in vec3 aPos;
        layout(location = 1) in vec3 aNormal;
        layout(location = 2) in vec2 aInfo;
        out vec3 vNormal;
        flat out float vType;
        flat out float vSelected;
        void main() {
            float selected = abs(aInfo.x - uSelected) < 0.5 ? 1.0 : 0.0;
            vNormal = aNormal;
            vType = aInfo.y;
            vSelected = selected;
            gl_Position = uMvp * vec4(aPos + selected * uDrag, 1.0);
        }"""

    /**
     * uPass 0: opaque parts (type 0, or 100 + 0-based filament); 1: translucent modifiers/negative volumes/blockers/enforcers and the
     * prime tower (type 99);
     * 2: paint overlay (types 5 fuzzy skin, 6/7 support enforce/block, 8/9 seam, 10+ filament).
     */
    const val MESH_FS = """#version 300 es
        precision mediump float;
        uniform int uPass;
        uniform vec3 uPartColor;
        uniform vec3 uSelectedColor;
        uniform vec3 uFilamentColors[16];
        in vec3 vNormal;
        flat in float vType;
        flat in float vSelected;
        out vec4 color;
        void main() {
            int type = int(vType + 0.5);
            bool part = type == 0 || type >= 100;
            if (uPass == 0 && !part) discard;
            // 99: the estimated prime tower (scene mesh, translucent pass).
            if (uPass == 1 && (part || (type >= 5 && type != 99))) discard;
            vec3 n = normalize(vNormal);
            float light = 0.35 + 0.65 * abs(dot(n, normalize(vec3(0.35, -0.55, 0.75))));
            vec3 base;
            float alpha = 1.0;
            if (part) {
                vec3 own = type >= 100 ? uFilamentColors[clamp(type - 100, 0, 15)] : uPartColor;
                base = mix(own, uSelectedColor, vSelected * 0.75);
            }
            else if (type == 1) { base = vec3(0.6, 0.6, 0.6); alpha = 0.45; }
            else if (type == 2) { base = vec3(0.95, 0.85, 0.3); alpha = 0.35; }
            else if (type == 3) { base = vec3(0.95, 0.25, 0.25); alpha = 0.4; }
            else if (type == 4) { base = vec3(0.25, 0.55, 1.0); alpha = 0.4; }
            else if (type == 5) base = vec3(0.35, 0.3, 0.45);
            else if (type == 6) base = vec3(0.2, 0.85, 0.35);
            else if (type == 7) base = vec3(0.95, 0.25, 0.25);
            else if (type == 8) base = vec3(0.3, 0.55, 1.0);
            else if (type == 9) base = vec3(1.0, 0.6, 0.1);
            else if (type == 99) { base = vec3(0.70, 0.89, 0.67); alpha = 0.5; }
            else base = uFilamentColors[clamp(type - 10, 0, 15)];
            color = vec4(base * light, alpha);
        }"""

    const val LINE_VS = """#version 300 es
        uniform mat4 uMvp;
        uniform vec3 uOffset;
        layout(location = 0) in vec3 aPos;
        void main() { gl_Position = uMvp * vec4(aPos + uOffset, 1.0); }"""

    const val LINE_FS = """#version 300 es
        precision mediump float;
        uniform vec3 uColor;
        out vec4 color;
        void main() { color = vec4(uColor, 1.0); }"""

    const val POINT_FS = """#version 300 es
        precision mediump float;
        in vec3 vColor;
        out vec4 color;
        void main() {
            vec2 c = gl_PointCoord - vec2(0.5);
            if (dot(c, c) > 0.25) discard;
            color = vec4(vColor, 1.0);
        }"""

    /** Markers: position + kind (0 retract, 1 unretract, 2 seam, 3 wipe, 4 measurement). */
    const val POINT_VS = """#version 300 es
        uniform mat4 uMvp;
        uniform vec3 uOffset;
        uniform int uKinds;
        layout(location = 0) in vec3 aPos;
        layout(location = 1) in float aKind;
        out vec3 vColor;
        void main() {
            int kind = int(aKind + 0.5);
            if (((uKinds >> kind) & 1) == 0) { gl_Position = vec4(2.0, 2.0, 2.0, 1.0); return; }
            vColor = kind == 0 ? vec3(0.9, 0.1, 0.9) : kind == 1 ? vec3(0.1, 0.8, 0.9) : kind == 2 ? vec3(0.95, 0.95, 0.95)
                   : kind == 3 ? vec3(1.0, 0.9, 0.0) : vec3(1.0, 0.3, 0.3);
            gl_Position = uMvp * vec4(aPos + uOffset, 1.0);
            gl_PointSize = kind == 4 ? 16.0 : 7.0;
        }"""

    /**
     * Instanced toolpath boxes. Per vertex: corner (u along the segment, side, vertical) and face
     * normal in the segment frame; per instance: segment ends, role, width, height, scheme value.
     */
    const val PATH_VS = """#version 300 es
        uniform mat4 uMvp;
        uniform vec3 uOrigin;
        uniform int uScheme;
        uniform int uHidden;
        uniform vec2 uRange;
        uniform vec3 uRoleColors[20];
        uniform vec3 uFilamentColors[16];
        layout(location = 0) in vec3 aCorner;
        layout(location = 1) in vec3 aFace;
        layout(location = 2) in vec3 aP0;
        layout(location = 3) in vec3 aP1;
        layout(location = 4) in float aRole;
        layout(location = 5) in float aWidth;
        layout(location = 6) in float aHeight;
        layout(location = 7) in float aValue;
        out vec3 vColor;
        out vec3 vNormal;

        vec3 gradient(float t) {
            t = clamp(t, 0.0, 1.0);
            vec3 c0 = vec3(0.04, 0.17, 0.48), c1 = vec3(0.07, 0.55, 0.83), c2 = vec3(0.13, 0.79, 0.38),
                 c3 = vec3(0.96, 0.84, 0.13), c4 = vec3(0.89, 0.24, 0.13);
            if (t < 0.25) return mix(c0, c1, t * 4.0);
            if (t < 0.5) return mix(c1, c2, (t - 0.25) * 4.0);
            if (t < 0.75) return mix(c2, c3, (t - 0.5) * 4.0);
            return mix(c3, c4, (t - 0.75) * 4.0);
        }

        void main() {
            int role = clamp(int(aRole + 0.5), 0, 19);
            if (((uHidden >> role) & 1) == 1) { gl_Position = vec4(2.0, 2.0, 2.0, 1.0); return; }
            vec3 d = aP1 - aP0;
            float len = length(d);
            vec3 dir = len > 1e-5 ? d / len : vec3(1.0, 0.0, 0.0);
            vec3 side = cross(dir, vec3(0.0, 0.0, 1.0));
            vec3 right = length(side) > 1e-4 ? normalize(side) : vec3(1.0, 0.0, 0.0);
            vec3 up = cross(right, dir);
            float w = max(aWidth, 0.05) * 0.5;
            float h = max(aHeight, 0.05);
            // Extend each segment by half its width so consecutive segments join without gaps.
            vec3 along = mix(aP0 - dir * w * 0.5, aP1 + dir * w * 0.5, aCorner.x);
            vec3 pos = along + right * aCorner.y * w + up * (aCorner.z * 0.5 - 0.5) * h + uOrigin;
            vNormal = normalize(right * aFace.x + up * aFace.y + dir * aFace.z);
            if (uScheme == 0) vColor = uRoleColors[role];
            else if (uScheme == 7) vColor = uFilamentColors[clamp(int(aValue + 0.5), 0, 15)];
            else vColor = gradient((aValue - uRange.x) / max(uRange.y - uRange.x, 1e-6));
            gl_Position = uMvp * vec4(pos, 1.0);
        }"""

    const val PATH_FS = """#version 300 es
        precision mediump float;
        in vec3 vColor;
        in vec3 vNormal;
        out vec4 color;
        void main() {
            float light = 0.4 + 0.6 * max(dot(normalize(vNormal), normalize(vec3(0.3, -0.5, 0.8))), 0.0);
            color = vec4(vColor * light, 1.0);
        }"""

    /** Colours per libslic3r ExtrusionRole (erNone ... erMixed), close to OrcaSlicer's defaults. */
    val ROLE_COLORS = floatArrayOf(
        0.5f, 0.5f, 0.5f,    // None
        1.0f, 0.90f, 0.30f,  // Perimeter (inner wall)
        1.0f, 0.49f, 0.22f,  // External perimeter
        0.12f, 0.12f, 1.0f,  // Overhang perimeter
        0.69f, 0.19f, 0.16f, // Internal (sparse) infill
        0.59f, 0.33f, 0.80f, // Solid infill
        0.94f, 0.25f, 0.25f, // Top surface
        0.40f, 0.36f, 0.78f, // Bottom surface
        1.0f, 0.55f, 0.41f,  // Ironing
        0.30f, 0.50f, 0.73f, // Bridge
        0.30f, 0.50f, 0.73f, // Internal bridge
        1.0f, 1.0f, 1.0f,    // Gap fill
        0.0f, 0.53f, 0.43f,  // Skirt
        0.0f, 0.53f, 0.43f,  // Brim
        0.0f, 1.0f, 0.0f,    // Support
        0.0f, 0.5f, 0.0f,    // Support interface
        0.0f, 0.8f, 0.4f,    // Support transition
        0.70f, 0.89f, 0.67f, // Prime tower
        0.37f, 0.82f, 0.58f, // Custom
        0.5f, 0.5f, 0.5f,    // Mixed
    )
}
