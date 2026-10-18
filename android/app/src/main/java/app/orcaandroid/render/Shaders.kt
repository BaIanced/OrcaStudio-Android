package app.orcaandroid.render

/** GLSL ES 3.0 programs of [PlateRenderer]. Attribute locations are fixed with layout qualifiers. */
internal object Shaders {

    /**
     * Fraction of the key light that reaches [sc] (light-space uv + depth): 0 shadow .. 1 lit. Golden-angle taps in a disc of
     * uSoft (uv), turned per pixel so the few taps blur instead of banding; each tap is a hardware 2x2 compare. The bias
     * (uBiasMm, over the 1040 mm depth range) shrinks on faces turned towards the light. Every tap's depth follows the
     * receiver's own slope (planeGrad, from the screen derivatives: call it outside any branch), so a wide disc on a tilted
     * face doesn't shadow itself (grain). highp: fp16 depth compares are all acne.
     */
    private const val PCF = """
        uniform mediump sampler2DShadow uRealShadowMap;
        uniform highp float uSoft;
        uniform highp float uBiasMm;
        highp vec2 planeGrad(highp vec3 sc) {
            highp vec2 dx = dFdx(sc.xy), dy = dFdy(sc.xy);
            highp float det = dx.x * dy.y - dx.y * dy.x;
            if (abs(det) < 1e-12) return vec2(0.0);
            return clamp(vec2(dFdx(sc.z) * dy.y - dFdy(sc.z) * dx.y, dFdy(sc.z) * dx.x - dFdx(sc.z) * dy.x) / det, -4.0, 4.0);
        }
        highp float lightAt(highp vec3 sc, highp vec2 grad, highp float ndl, int taps) {
            highp float z = sc.z - uBiasMm * max(1.0 - ndl, 0.15) / 1040.0;
            highp float rot = 6.2832 * fract(52.9829189 * fract(dot(gl_FragCoord.xy, vec2(0.06711056, 0.00583715))));
            highp float s = 0.0;
            for (int i = 0; i < taps; ++i) {
                highp float a = float(i) * 2.39996 + rot;
                highp float r = sqrt((float(i) + 0.5) / float(taps)) * uSoft;
                highp vec2 o = vec2(cos(a), sin(a)) * r;
                s += texture(uRealShadowMap, vec3(sc.xy + o, z + dot(o, grad)));
            }
            return s / float(taps);
        }"""

    /** Selected copies the mesh shader highlights and drags (more are selected but not drawn so). */
    const val MAX_SELECTED = 64

    /**
     * Scene mesh: position, normal, (selection id = object * INSTANCE_ID_STRIDE + instance, volume
     * type; 100 + filament for parts).
     */
    const val MESH_VS = """#version 300 es
        uniform mat4 uMvp;
        uniform mat4 uLightMvp;
        uniform float uSelected[64];
        uniform int uSelectedCount;
        uniform vec3 uDrag;
        layout(location = 0) in vec3 aPos;
        layout(location = 1) in vec3 aNormal;
        layout(location = 2) in vec2 aInfo;
        out vec3 vNormal;
        out highp vec3 vWorld;
        out highp vec4 vShadowCoord;
        flat out float vType;
        flat out float vSelected;
        void main() {
            float selected = 0.0;
            for (int i = 0; i < uSelectedCount; ++i)
                if (abs(aInfo.x - uSelected[i]) < 0.5) selected = 1.0;
            vNormal = aNormal;
            vType = aInfo.y;
            vSelected = selected;
            vWorld = aPos + selected * uDrag;
            // Normal offset (0.4 mm): the receiver is looked up a little outside its own surface, against acne.
            vShadowCoord = uLightMvp * vec4(vWorld + aNormal * 0.4, 1.0);
            gl_Position = uMvp * vec4(vWorld, 1.0);
        }"""

    /**
     * uPass 0: opaque parts (type 0, or 100 + 0-based filament); 1: translucent modifiers/negative volumes/blockers/enforcers and the
     * prime tower (type 99);
     * 2: paint overlay (types 5 fuzzy skin, 6/7 support enforce/block, 8/9 seam, 10+ filament);
     * 3: contact shadow map (parts seen from above; how close to the bed, max-blended);
     * 4: depth-only pass of the real shadow map (parts seen from the key light).
     */
    const val MESH_FS = """#version 300 es
        precision mediump float;
        uniform int uPass;
        uniform float uShadowFade;
        uniform vec3 uPartColor;
        uniform vec3 uSelectedColor;
        uniform vec3 uFilamentColors[16];
        uniform highp vec4 uSection;
        uniform int uSectionOn;
        uniform highp vec3 uEye;
        """ + PCF + """
        uniform int uUseRealShadow;
        uniform float uShadowStrength;
        uniform vec3 uKeyDir;
        uniform float uKeyStrength;
        in vec3 vNormal;
        in highp vec3 vWorld;
        in highp vec4 vShadowCoord;
        flat in float vType;
        flat in float vSelected;
        out vec4 color;
        void main() {
            int type = int(vType + 0.5);
            bool part = type == 0 || type >= 100;
            if (uPass == 4) {
                // Real shadow map: depth only; parts cast, cut-away geometry doesn't.
                if (!part || (uSectionOn == 1 && dot(uSection.xyz, vWorld) > uSection.w)) discard;
                return;
            }
            if (uPass == 3) {
                // Contact shadow: geometry near the bed casts most, fading out by uShadowFade mm up.
                if (!part || (uSectionOn == 1 && dot(uSection.xyz, vWorld) > uSection.w)) discard;
                float k = 1.0 - clamp(vWorld.z / uShadowFade, 0.0, 1.0);
                color = vec4(k * k);
                return;
            }
            if (uPass == 0 && !part) discard;
            // 99: the estimated prime tower (scene mesh, translucent pass).
            if (uPass == 1 && (part || (type >= 5 && type != 99 && type != 98))) discard;
            if (uSectionOn == 1) {
                // Section view: the camera side of the plane is cut away. Inside faces seen through
                // the cut stand in for the desktop's cut faces (MeshClipper, dark grey).
                if (dot(uSection.xyz, vWorld) > uSection.w) discard;
                if (!gl_FrontFacing) {
                    if (uPass != 0) discard;
                    color = vec4(0.25, 0.25, 0.25, 1.0);
                    return;
                }
            }
            vec3 n = normalize(vNormal);
            float light = 0.35 + 0.65 * abs(dot(n, normalize(vec3(0.35, -0.55, 0.75))));
            vec3 base;
            float alpha = 1.0;
            if (part) {
                vec3 own = type >= 100 ? uFilamentColors[clamp(type - 100, 0, 15)] : uPartColor;
                // Very dark filaments keep some shading (black would be a flat silhouette).
                own = max(own, vec3(0.13));
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
            else if (type == 98) { base = vec3(0.96, 0.58, 0.2); alpha = 0.45; }
            else base = uFilamentColors[clamp(type - 10, 0, 15)];
            if (part) {
                // Soft studio look for the model: sky/ground ambient (z is up), a key and a fill light, a
                // faint plastic highlight and a cool rim on silhouettes. The slice preview keeps its flat colours.
                vec3 v = normalize(uEye - vWorld);
                if (dot(n, v) < 0.0) n = -n;
                vec3 lk = normalize(uKeyDir);
                vec3 lf = normalize(vec3(-0.6, 0.4, 0.35));
                float sky = 0.5 + 0.5 * n.z;
                // Real shadow (highp: the depth compare needs it; fp16 would be all acne). Darkens a face as much as the
                // bed's shadow at the same darkness (dropping only the key term left it faint at 50 %); faces turned
                // from the key light are left alone (already unlit; darkening them again would double the shade).
                float ndl = max(dot(n, lk), 0.0);
                float lit = 1.0;
                if (uUseRealShadow == 1 && uPass == 0) {
                    highp vec3 sc = vShadowCoord.xyz / vShadowCoord.w * 0.5 + 0.5;
                    highp vec2 grad = planeGrad(sc);
                    if (sc.z < 1.0 && sc.x > 0.0 && sc.x < 1.0 && sc.y > 0.0 && sc.y < 1.0) {
                        float occ = 1.0 - lightAt(sc, grad, dot(normalize(vNormal), lk), 8);
                        lit = 1.0 - uShadowStrength * occ * smoothstep(0.0, 0.2, ndl);
                    }
                }
                float diff = 0.22 + 0.16 * sky + 0.50 * uKeyStrength * ndl + 0.20 * max(dot(n, lf), 0.0);
                float spec = pow(max(dot(n, normalize(lk + v)), 0.0), 36.0) * 0.16 * uKeyStrength;
                float rim = pow(1.0 - max(dot(n, v), 0.0), 3.0) * 0.14;
                vec3 shaded = (base * diff + vec3(spec)) * lit + vec3(0.55, 0.7, 1.0) * rim * (0.4 + 0.6 * sky);
                color = vec4(shaded, alpha);
                return;
            }
            color = vec4(base * light, alpha);
        }"""

    const val LINE_VS = """#version 300 es
        uniform mat4 uMvp;
        uniform vec3 uOffset;
        layout(location = 0) in vec3 aPos;
        out highp vec3 vWorld;
        void main() { vWorld = aPos + uOffset; gl_Position = uMvp * vec4(vWorld, 1.0); }"""

    /** Beds, travels and overlays; travels are cut by the section view like the toolpaths. */
    const val LINE_FS = """#version 300 es
        precision mediump float;
        uniform vec3 uColor;
        uniform highp vec4 uSection;
        uniform int uSectionOn;
        in highp vec3 vWorld;
        out vec4 color;
        void main() {
            if (uSectionOn == 1 && dot(uSection.xyz, vWorld) > uSection.w) discard;
            color = vec4(uColor, 1.0);
        }"""

    /**
     * The bed's 10 mm grid as one quad per bed (uRect, plate-local x0, y0, x1, y1, moved by uOffset): each line is
     * drawn from its distance in pixels, so it stays 1 px wide and smooth instead of the stepped, broken GL_LINES of
     * near-top views, and a direction whose lines crowd from ~8 to ~3 px apart fades out instead of shimmering into dashes.
     */
    const val GRID_VS = """#version 300 es
        uniform mat4 uMvp;
        uniform highp vec4 uRect;
        uniform vec3 uOffset;
        out highp vec2 vLocal;
        void main() {
            vec2 c = vec2(float(gl_VertexID & 1), float((gl_VertexID >> 1) & 1));
            vLocal = mix(uRect.xy, uRect.zw, c);
            gl_Position = uMvp * vec4(vLocal + uOffset.xy, 0.0, 1.0);
        }"""

    const val GRID_FS = """#version 300 es
        precision highp float;
        uniform vec3 uColor;
        uniform vec4 uRect;
        in vec2 vLocal;
        out vec4 color;
        float axis(float g, float w, float lo, float hi) {
            // Lines on the bed's edge belong to the outline.
            float at = floor(g + 0.5) * 10.0;
            if (at < lo + 0.01 || at > hi - 0.01) return 0.0;
            float d = abs(g - floor(g + 0.5)) / max(w, 1e-6);
            return clamp(1.0 - d, 0.0, 1.0) * (1.0 - smoothstep(0.12, 0.3, w));
        }
        void main() {
            vec2 g = vLocal / 10.0;
            vec2 w = fwidth(g);
            float a = max(axis(g.x, w.x, uRect.x, uRect.z), axis(g.y, w.y, uRect.y, uRect.w));
            if (a < 0.004) discard;
            color = vec4(uColor, a);
        }"""

    /** Plate badges (numbers, icons): textured quads on the bed plane from a premultiplied atlas. */
    const val BADGE_VS = """#version 300 es
        uniform mat4 uMvp;
        layout(location = 0) in vec3 aPos;
        layout(location = 1) in vec2 aUv;
        out vec2 vUv;
        void main() { vUv = aUv; gl_Position = uMvp * vec4(aPos, 1.0); }"""

    const val BADGE_FS = """#version 300 es
        precision mediump float;
        uniform sampler2D uAtlas;
        in vec2 vUv;
        out vec4 color;
        void main() { color = texture(uAtlas, vUv); }"""

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
        uniform highp vec4 uSection;
        uniform int uSectionOn;
        layout(location = 0) in vec3 aPos;
        layout(location = 1) in float aKind;
        out vec3 vColor;
        void main() {
            int kind = int(aKind + 0.5);
            vec3 world = aPos + uOffset;
            // Hidden kinds and markers on the section view's cut-away side go off screen.
            if (((uKinds >> kind) & 1) == 0 || (uSectionOn == 1 && dot(uSection.xyz, world) > uSection.w)) {
                gl_Position = vec4(2.0, 2.0, 2.0, 1.0); return;
            }
            vColor = kind == 0 ? vec3(0.9, 0.1, 0.9) : kind == 1 ? vec3(0.1, 0.8, 0.9) : kind == 2 ? vec3(0.95, 0.95, 0.95)
                   : kind == 3 ? vec3(1.0, 0.9, 0.0) : vec3(1.0, 0.3, 0.3);
            gl_Position = uMvp * vec4(world, 1.0);
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
        // Index of the first drawn segment, so a segment's depth order (below) doesn't change with the layer slider.
        uniform int uFirst;
        layout(location = 0) in vec3 aCorner;
        layout(location = 1) in vec3 aFace;
        layout(location = 2) in vec3 aP0;
        layout(location = 3) in vec3 aP1;
        layout(location = 4) in float aRole;
        layout(location = 5) in float aWidth;
        layout(location = 6) in float aHeight;
        layout(location = 7) in float aValue;
        // Ends of the previous and next segments in the buffer (far away at its ends): they mitre the joints.
        layout(location = 8) in vec3 aPrevP0;
        layout(location = 9) in vec3 aPrevP1;
        layout(location = 10) in vec3 aNextP0;
        layout(location = 11) in vec3 aNextP1;
        out vec3 vColor;
        out vec3 vNormal;
        out highp vec3 vWorld;
        out float vBead;
        out float vAcross;
        out vec3 vRight;
        flat out float vSideFace;
        flat out float vTopFace;

        vec3 rightOf(vec3 a, vec3 b) {
            vec3 s = cross(b - a, vec3(0.0, 0.0, 1.0));
            return length(s) > 1e-6 ? normalize(s) : vec3(0.0);
        }

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
            // Same function as for the neighbours' sides below, so a joint's two segments agree bit for bit.
            vec3 right = rightOf(aP0, aP1);
            if (dot(right, right) < 0.5) right = vec3(1.0, 0.0, 0.0);
            vec3 up = cross(right, dir);
            float w = max(aWidth, 0.05) * 0.5;
            float h = max(aHeight, 0.05);
            // A joint with the segment before / after (same point) is mitred: the end face bisects the turn, so
            // the boxes neither overlap nor leave a notch (their end caps poked out as dark dashes and moire).
            // Gentle bends also share the side normal, so curved walls shade smoothly instead of in facets.
            bool atEnd = aCorner.x > 0.5;
            vec3 rn = atEnd ? (distance(aNextP0, aP1) < 1e-3 ? rightOf(aNextP0, aNextP1) : vec3(0.0))
                            : (distance(aPrevP1, aP0) < 1e-3 ? rightOf(aPrevP0, aPrevP1) : vec3(0.0));
            float c = dot(rn, right);
            vec3 across;
            vec3 sideNormal = right;
            if (dot(rn, rn) > 0.5 && c > -0.5) {
                // (right + rn) / (1 + c) is symmetric in the two segments, so both compute the same corner
                // bit for bit (a watertight joint; any difference left hairline cracks along the layers).
                across = (right + rn) * (aCorner.y * w / (1.0 + c));
                if (c > 0.77) sideNormal = normalize(right + rn);
            } else {
                // Open end or sharp turn (over 120 degrees): extend by a quarter width so the corner has no gap.
                across = right * aCorner.y * w + dir * (atEnd ? 0.5 : -0.5) * w;
            }
            // The layer's height goes straight down (exact z), so neighbouring boxes share their edges exactly.
            vec3 pos = (atEnd ? aP1 : aP0) + across + vec3(0.0, 0.0, (aCorner.z * 0.5 - 0.5) * h) + uOrigin;
            // The bottom reaches a little into the layer below: the layers' edges never share vertices (T-junctions),
            // so single-pixel cracks showed the darker inside through them and danced as the camera moved (user,
            // 2026-10-17). The overlap is pushed back in depth below, so the layer below keeps its own top edge.
            bool bottom = aCorner.z < 0.0;
            if (bottom) pos.z -= clamp(pos.z, 0.0, 0.15 * h);
            vNormal = normalize(sideNormal * aFace.x + up * aFace.y + dir * aFace.z);
            vBead = aCorner.z;
            vSideFace = abs(aFace.x);
            // Top / bottom faces are rounded across the bead's width in the fragment shader: u spans -1..1 over it.
            vTopFace = abs(aFace.y);
            vAcross = aCorner.y;
            vRight = sideNormal;
            if (uScheme == 0) vColor = uRoleColors[role];
            else if (uScheme == 7) vColor = uFilamentColors[clamp(int(aValue + 0.5), 0, 15)];
            else vColor = gradient((aValue - uRange.x) / max(uRange.y - uRange.x, 1e-6));
            vWorld = pos;
            // Beads of different roles overlap on the same plane (skin ends run into the walls): which one won the
            // depth test flipped with every camera move (the sawtooth that strobed). A fixed lift per role makes the
            // winner the same from every angle: walls over gap fill over top skin over solid infill over sparse infill.
            float lift = role == 2 ? 4.0 : (role == 1 || role == 3) ? 3.0 : (role == 6 || role == 8 || role == 11) ? 2.0
                : (role == 5 || role == 7 || role == 9 || role == 10) ? 1.0 : 0.0;
            gl_Position = uMvp * vec4(pos + vec3(0.0, 0.0, lift * 0.01), 1.0);
            // Fixed depth order for faces on the same plane (a few depth-buffer steps each, any zoom): the role as
            // above, then the line's direction in three 60 degree bins (crossing infill lines of one role: parity
            // alone tied whenever both lines had the same, user r294), then odd segments over even ones (a loop's
            // seam ends, neighbouring parallel lines). Bin edges sit at 7.5 + 60k degrees, off the usual infill angles.
            float a = atan(dir.y, dir.x) - 0.1309;
            float bin = mod(floor(a * 0.9549297), 3.0);
            float order = lift * 6.0 + bin * 2.0 + float((gl_InstanceID + uFirst) & 1);
            gl_Position.z -= order * 5e-7 * gl_Position.w;
            // The bottom overlap goes behind all of it.
            if (bottom) gl_Position.z += 1.6e-5 * gl_Position.w;
        }"""

    const val PATH_FS = """#version 300 es
        precision mediump float;
        uniform highp vec4 uSection;
        uniform int uSectionOn;
        in vec3 vColor;
        in vec3 vNormal;
        in highp vec3 vWorld;
        in float vBead;
        in float vAcross;
        in vec3 vRight;
        flat in float vSideFace;
        flat in float vTopFace;
        out vec4 color;
        void main() {
            if (uSectionOn == 1 && dot(uSection.xyz, vWorld) > uSection.w) discard;
            // Same soft key/fill/sky light as the model (no highlight: it would shimmer on layer lines),
            // so faces turned from the key light keep their shape instead of dropping to one dark tone.
            vec3 n = normalize(vNormal);
            // Side walls are rounded like a real bead (normal tilts up at the top of each layer, down at the
            // bottom). It fades out below ~8 px per layer (fwidth 0.25; vBead spans 2 per layer): smaller
            // layers beat against the pixel grid into wavy moire.
            float bead = vSideFace * 0.8 * (1.0 - smoothstep(0.12, 0.25, fwidth(vBead)));
            n = normalize(n + vec3(0.0, 0.0, vBead * bead));
            // Top and bottom skins read as separate extrusions: the normal rolls off towards the bead's edges
            // and the edge itself darkens a little. Fades out when a bead is under ~8 px wide (moire).
            float rounding = vTopFace * (1.0 - smoothstep(0.12, 0.25, fwidth(vAcross)));
            n = normalize(n + vRight * (vAcross * 0.9 * rounding));
            float sky = 0.5 + 0.5 * n.z;
            float light = 0.30 + 0.12 * sky + 0.42 * max(dot(n, normalize(vec3(0.35, -0.55, 0.75))), 0.0)
                + 0.24 * max(dot(n, normalize(vec3(-0.6, 0.4, 0.35))), 0.0);
            light *= 1.0 - 0.3 * rounding * pow(abs(vAcross), 6.0);
            color = vec4(vColor * light, 1.0);
        }"""

    /** Full-screen triangle (no vertex buffer): vUv covers the target 0..1. */
    const val FULLSCREEN_VS = """#version 300 es
        out vec2 vUv;
        void main() {
            vec2 p = vec2(float((gl_VertexID << 1) & 2), float(gl_VertexID & 2));
            vUv = p;
            gl_Position = vec4(p * 2.0 - 1.0, 0.0, 1.0);
        }"""

    /** Separable Gaussian (9 taps folded into 5 linear samples) along uStep (uv per tap unit). */
    const val BLUR_FS = """#version 300 es
        precision mediump float;
        uniform sampler2D uTex;
        uniform vec2 uStep;
        in vec2 vUv;
        out vec4 color;
        void main() {
            vec4 c = texture(uTex, vUv) * 0.2270270270;
            c += (texture(uTex, vUv + uStep * 1.3846153846) + texture(uTex, vUv - uStep * 1.3846153846)) * 0.3162162162;
            c += (texture(uTex, vUv + uStep * 3.2307692308) + texture(uTex, vUv - uStep * 3.2307692308)) * 0.0702702703;
            color = c;
        }"""

    /**
     * The blurred contact shadow map laid on the bed just under z = 0: the map covers uRect (x0, y0, x1, y1), the quad
     * is uClip (that rect cut to one bed, so a long shadow doesn't hang in the air past the bed's edge).
     */
    const val SHADOW_VS = """#version 300 es
        uniform mat4 uMvp;
        uniform mat4 uLightMvp;
        uniform highp vec4 uRect;
        uniform highp vec4 uClip;
        out vec2 vUv;
        out highp vec4 vShadowCoord;
        void main() {
            vec2 c = vec2(float(gl_VertexID & 1), float((gl_VertexID >> 1) & 1));
            highp vec2 xy = mix(uClip.xy, uClip.zw, c);
            vUv = (xy - uRect.xy) / (uRect.zw - uRect.xy);
            vShadowCoord = uLightMvp * vec4(xy, 0.0, 1.0);
            gl_Position = uMvp * vec4(xy, -0.02, 1.0);
        }"""

    const val SHADOW_FS = """#version 300 es
        precision mediump float;
        uniform sampler2D uTex;
        """ + PCF + """
        uniform int uReal;
        uniform float uStrength;
        uniform float uBedNdl;
        in vec2 vUv;
        in highp vec4 vShadowCoord;
        out vec4 color;
        void main() {
            if (uReal == 0) { color = vec4(0.0, 0.0, 0.0, uStrength * texture(uTex, vUv).r); return; }
            // Real shadow on the bed (flat, facing up: uBedNdl = the key light's height, z of its direction).
            highp vec3 sc = vShadowCoord.xyz / vShadowCoord.w * 0.5 + 0.5;
            float lit = 1.0;
            highp vec2 grad = planeGrad(sc);
            if (sc.z < 1.0 && sc.x > 0.0 && sc.x < 1.0 && sc.y > 0.0 && sc.y < 1.0) lit = lightAt(sc, grad, uBedNdl, 16);
            color = vec4(0.0, 0.0, 0.0, uStrength * (1.0 - lit));
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
