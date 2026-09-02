// Exponential height fog, integrated analytically per path segment. Depends on sky and bindings.
//
// This is aerial perspective for the block-scale world, not the planet-scale atmosphere: sky.slang's LUTs
// already carry Rayleigh/Mie/ozone over 100 km, but their density at the 0-320 block range the player
// actually looks through is far too low to read as fog. This module adds a second, thin, tunable medium
// that lives entirely inside the render distance.
//
// It is applied as an AFFINE operation on radiance, not as a throughput multiply. A throughput-only
// exp(-sigma*t) — which is all the existing water/glass path does — is pure absorption, and pure
// absorption sends the horizon to black rather than to sky colour. Fog needs the in-scatter term too:
//
//     L' = L * T + Lin * (1 - T)
//
// so every call site has to hand over both the accumulated radiance and the throughput. That is why this
// is a helper taking two inout parameters rather than something foldable into MediumStack: the medium
// stack is deliberately a pure-extinction abstraction and this would have quietly broken that invariant
// for water and glass as well.
//
// The in-scatter radiance is the SKY VIEW LUT sampled in the ray's own direction, not a configured
// colour. That is the whole reason this reads as atmosphere rather than as a grey wash: fog at sunset is
// orange toward the sun and blue away from it, fog at night falls to the airglow floor, and fog under an
// overcast look package tracks whatever the LUT bake produced — all for free, and all guaranteed to match
// the sky the miss shader draws, because it is literally the same texture.
//
// Transmittance is deliberately scalar rather than per-channel. Real fog is Mie-dominated and close to
// spectrally flat at these distances, and a scalar T lets the whole thing cost one exp and one LUT pair.

import world_common;
import sky;
import medium;
import bindings;
import lighting;

// The trace tmax. Integrating a miss out to this distance is correct rather than arbitrary: an upward ray
// converges (the exponential integral is finite as t goes to infinity), and a horizontal ray at the
// reference height saturates to full fog, which is exactly what a fogged horizon should do.
public static const float FOG_MAX_DISTANCE = 10000.0;

// Keeps exp() finite for a viewer far under the reference plane or a steep downward ray in a thin fog.
// e^60 is already past anything that survives the 1 - T below, so clamping here costs nothing visible.
static const float FOG_EXP_CLAMP = 60.0;

public struct FogParams {
    public float sigma;       // extinction per block at referenceY. 0 disables the whole module.
    public float falloff;     // 1 / scale height, per block
    public float referenceY;  // rebased world Y at which density is sigma
    public float albedo;      // single-scattering albedo; 1 is a perfectly scattering (non-absorbing) fog
};

public FogParams makeFogParams(WorldPush push) {
    FogParams f;
    f.sigma = max(push.fog.x, 0.0);
    f.falloff = max(push.fog.y, 0.0);
    f.referenceY = push.fog.z;
    f.albedo = clamp(push.fog.w, 0.0, 1.0);
    return f;
}

public bool fogActive(FogParams f) {
    return f.sigma > 0.0;
}

static const int WEATHER_SNOW = 1;
static const int WEATHER_SAND = 2;

float weatherFogStructure(WorldPush push, float3 position) {
    float intensity = clamp(push.fog2.z, 0.0, 1.0);
    int weatherType = int(round(push.fog2.y));
    if (intensity <= 0.0 || (weatherType != WEATHER_SNOW && weatherType != WEATHER_SAND)) {
        return 1.0;
    }
    float time = push.waterParams.w;
    float3 coord;
    if (weatherType == WEATHER_SAND) {
        // Broad horizontal sheets moving faster than the cloud deck. One filtered texture fetch gives
        // a stable density field without per-ray procedural noise or frame-to-frame sparkling.
        coord = float3((position.x + time * 22.0) * 0.010, position.y * 0.035,
                       (position.z + time * 8.0) * 0.010);
    } else {
        // Snowfall varies more vertically and drifts slowly sideways. This is the participating-medium
        // component; visible vanilla/particle flakes remain a separate foreground layer.
        coord = float3((position.x + time * 2.5) * 0.022, (position.y + time * 9.0) * 0.030,
                       (position.z + time * 1.5) * 0.022);
    }
    float n = cloudNoiseTex.SampleLevel(coord, 0.0).x;
    float structured = weatherType == WEATHER_SAND ? lerp(0.55, 1.55, n) : lerp(0.72, 1.32, n);
    return lerp(1.0, structured, intensity);
}

float3 applyWeatherFogTint(float3 inscatter, WorldPush push) {
    float intensity = clamp(push.fog2.z, 0.0, 1.0);
    int weatherType = int(round(push.fog2.y));
    if (intensity <= 0.0 || weatherType == 0) {
        return inscatter;
    }
    float3 tint709 = weatherType == WEATHER_SAND
            ? float3(0.82, 0.50, 0.24) : float3(0.82, 0.90, 1.0);
    float3 tint = bt709ToAcesCg(tint709);
    float tintLuma = max(dot(tint, ACESCG_LUMA), 1.0e-5);
    float strength = intensity * (weatherType == WEATHER_SAND ? 0.78 : 0.38);
    return lerp(inscatter, inscatter * (tint / tintLuma), strength);
}

// One nearby emitter sample tints the participating medium around block lights. It intentionally casts
// no extra visibility ray: the fog term is low-frequency, and adding a shadow query to every path segment
// would cost more than the visual precision it buys. The light-grid lookup keeps this O(1).
float3 fogLocalLightInscatter(WorldPush push, float3 position) {
    if (pc.lightBufAddr == 0 || pc.lightGridCellAddr == 0 || pc.lightGridSpanAddr == 0
            || push.lightCount == 0u) {
        return float3(0.0);
    }
    LightGridCell cell;
    int3 cellCoord;
    if (!findLightGridCell(position, cell, cellCoord) || cell.spanCount == 0u) {
        return float3(0.0);
    }
    LightGridSpan span = ConstPtr<LightGridSpan>(pc.lightGridSpanAddr)[cell.spanOffset];
    uint count = span.packedLightCounts & 0xffffu;
    if (count == 0u || span.firstLight >= push.lightCount) {
        return float3(0.0);
    }
    Light light = ConstPtr<Light>(pc.lightBufAddr)[span.firstLight];
    float3 lightPos = light.pos + push.lightRebase.xyz;
    float3 toReceiver = position - lightPos;
    float dist2 = max(dot(toReceiver, toReceiver), 1.0);
    float3 dir = toReceiver * rsqrt(dist2);
    float facing = max(dot(lightGeometricNormal(light), dir), 0.0);
    if (facing <= 0.0) {
        return float3(0.0);
    }
    float solidAngle = min(lightArea(light) * facing / dist2, 0.35);
    return lightRadiance(light) * (solidAngle * ISOTROPIC_PHASE);
}

// Fog exists in air only. Inside water or glass that medium's own Beer-Lambert extinction is already the
// correct and complete answer, and layering a second medium on top would double-count the attenuation.
public bool fogAppliesTo(Medium m) {
    return !m.water && all(m.extinction <= 0.0);
}

// Optical depth of an exponential-density slab along ro + rd*s for s in [0, t].
//
//   n(y)  = exp(-(y - referenceY) * falloff)
//   tau   = sigma * integral of n(ro.y + rd.y*s) ds
//         = sigma * a * (1 - exp(-b*t)) / b,   a = n(ro.y), b = rd.y * falloff
//
// The b -> 0 limit (a level ray, which is the common case when looking at the horizon) is the removable
// singularity sigma*a*t, taken by an explicit branch rather than by an epsilon in the denominator.
public float fogOpticalDepth(FogParams f, float3 ro, float3 rd, float t) {
    float travel = clamp(t, 0.0, FOG_MAX_DISTANCE);
    if (travel <= 0.0) {
        return 0.0;
    }
    float density = exp(-clamp((ro.y - f.referenceY) * f.falloff, -FOG_EXP_CLAMP, FOG_EXP_CLAMP));
    float b = rd.y * f.falloff;
    float integral;
    if (abs(b) < 1.0e-5) {
        integral = density * travel;
    } else {
        integral = density * (1.0 - exp(-clamp(b * travel, -FOG_EXP_CLAMP, FOG_EXP_CLAMP))) / b;
    }
    return f.sigma * max(integral, 0.0);
}

// Radiance scattered into the ray by the fog. Two LUT fetches, matching the miss shader's skyDome: the
// sun slice plus the moon slice, then the isotropic airglow floor so night fog settles at the same
// luminance the night sky does instead of going black. sky.slang works in linear BT.709; the path
// integral is ACEScg, so this crosses the same seam the miss shader does, in the same place.
// Vanilla's FOG_COLOR for wherever the camera is, and how hard to pull scattering toward it. In the
// Overworld this is a light tint over the physically computed sky, so a swamp reads green-grey without
// losing the atmosphere's time-of-day behaviour. In the Nether and the End there is no atmosphere worth
// sampling, so the caller sends full strength and this becomes the entire fog colour.
public float3 fogTint(FogParams f, float3 inscatter, WorldPush push) {
    float strength = clamp(push.dimFog.w, 0.0, 1.0);
    float3 tinted = inscatter;
    if (strength > 0.0) {
        // Hue-only vanilla tint: missing/black environment colour must never remove atmospheric light.
        float3 tint = bt709ToAcesCg(max(push.dimFog.rgb, float3(0.0)));
        float tintLuma = dot(tint, ACESCG_LUMA);
        if (tintLuma > 1.0e-3) {
            tinted = lerp(tinted, tinted * (tint / tintLuma), strength);
        }
    }
    return applyWeatherFogTint(tinted, push);
}

public float3 fogInscatter(FogParams f, float3 rd, SkyState state, WorldPush push) {
    // The sky-view LUT parameterisation stops at the horizon. Terrain rays often point slightly below
    // it, so use the nearest valid horizon sample for the scattering direction while retaining the true
    // light-relative angle. This removes the black lower-border failure without flattening sunset colour.
    float horizonCos = horizonZenithCos(state.viewerRadiusKm);
    float lookupZenithCos = max(rd.y, horizonCos + 1.0e-3);
    float2 sunUv = skyViewLutUv(state.viewerRadiusKm, lookupZenithCos,
            lightViewCosine(rd, state.sunDir), SKY_VIEW_BODY_SUN);
    float2 moonUv = skyViewLutUv(state.viewerRadiusKm, lookupZenithCos,
            lightViewCosine(rd, state.moonDir), SKY_VIEW_BODY_MOON);
    float3 sky709 = skyViewLut.SampleLevel(sunUv, 0.0).rgb
            + skyViewLut.SampleLevel(moonUv, 0.0).rgb
            + float3(state.airglowLuminance);
    // Defensive edge fallback for an uninitialised/invalid LUT texel. Use the dimension sky colour as a
    // hue-bearing floor, never black; normal valid LUT samples never take this branch.
    if (dot(sky709, float3(0.2126, 0.7152, 0.0722)) <= 1.0e-6) {
        sky709 = max(push.dimSky.rgb, float3(max(state.airglowLuminance, 1.0e-5)));
    }
    return bt709ToAcesCg(max(sky709, float3(0.0))) * f.albedo;
}

// Fold one travelled segment into the path. Call once per segment, BEFORE the segment's endpoint
// contributes anything to L, so that the in-scatter enters at this segment's throughput and everything
// beyond it is attenuated by this segment's transmittance.
// Debug: replace the path radiance with the raw fog in-scatter colour so LUT/tint state can be
// inspected directly without the surface behind it.
public bool fogDebugInscatter(FogParams f, WorldPush push, float3 rd, SkyState state,
                              inout float3 L, inout float3 throughput) {
    if (push.fog2.x < 0.5) {
        return false;
    }
    L = fogTint(f, fogInscatter(f, rd, state, push), push);
    throughput = float3(0.0);
    return true;
}

public void applyFogSegment(FogParams f, SkyState state, Medium medium, WorldPush push,
                            float3 ro, float3 rd, float t,
                            inout float3 L, inout float3 throughput) {
    if (fogDebugInscatter(f, push, rd, state, L, throughput)) {
        return;
    }
    if (!fogActive(f) || !fogAppliesTo(medium)) {
        return;
    }
    float tau = fogOpticalDepth(f, ro, rd, t);
    if (tau <= 0.0) {
        return;
    }
    float travel = clamp(t, 0.0, FOG_MAX_DISTANCE);
    float3 midpoint = ro + rd * (travel * 0.5);
    tau *= weatherFogStructure(push, midpoint);
    float transmittance = exp(-tau);
    float3 inscatter = fogTint(f, fogInscatter(f, rd, state, push), push);
    inscatter += fogLocalLightInscatter(push, midpoint) * f.albedo;
    L += throughput * inscatter * (1.0 - transmittance);
    throughput *= transmittance;
}
