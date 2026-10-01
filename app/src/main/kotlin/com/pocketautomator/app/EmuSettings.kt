package com.pocketautomator.app

import org.json.JSONObject
import java.io.File
import java.io.RandomAccessFile

/**
 * The emulators whose settings Pocket Automator can set per game. RetroArch's
 * cores share RetroArch's app: [core] is the core's library name (the folder
 * RetroArch keeps its options in), and [prefix] starts each of its option keys.
 */
enum class Emulator(val pkg: String, val title: String, val core: String? = null, val prefix: String = "") {
    EDEN("dev.eden.eden_emulator", "Eden"),
    DOLPHIN("org.dolphinemu.dolphinemu", "Dolphin"),
    AZAHAR("org.azahar_emu.azahar", "Azahar"),
    ARMSX2("com.armsx2", "ARMSX2"),
    DUCKSTATION("com.github.stenzek.duckstation", "DuckStation"),
    SWANSTATION(RetroArch.PACKAGE, "SwanStation", "SwanStation", "swanstation_"),
    MUPEN64(RetroArch.PACKAGE, "Mupen64Plus-Next", "Mupen64Plus-Next", "mupen64plus-"),
    FLYCAST(RetroArch.PACKAGE, "Flycast", "Flycast", "reicast_");

    val isRetroArch: Boolean get() = core != null

    companion object {
        /** A standalone emulator by its app. */
        fun of(pkg: String?): Emulator? = entries.firstOrNull { !it.isRetroArch && it.pkg == pkg }

        /**
         * The emulator that runs a game: its app ([pkg], see [EsDe.emulatorFor]),
         * and in RetroArch the core ES-DE starts ([label], else the system's default).
         */
        fun forGame(pkg: String?, label: String?, system: String): Emulator? = when {
            pkg == null -> null
            pkg in RetroArch.PACKAGES -> RetroArch.coreFor(label, system)
            else -> of(pkg)
        }
    }
}

data class EmuOption(val value: String, val label: String)

/**
 * One emulator setting a game can have its own value for. [section] and
 * [name] say where it lives in the emulator's ini file.
 */
data class EmuKnob(
    val key: String,
    val emulator: Emulator,
    val title: String,
    val section: String,
    val name: String,
    val options: List<EmuOption>,
    /** What it does, in a line. */
    val hint: String? = null,
    /** Shown under "More settings". */
    val advanced: Boolean = false,
    /** The emulator's own value when its config leaves it blank. */
    val default: String? = null,
    /** Where the emulator-wide value lives, when it isn't the same file, section and name. */
    val globalFile: String? = null,
    val globalSection: String? = null,
    val globalName: String? = null,
) {
    fun label(value: String?): String? = options.firstOrNull { it.value == value }?.label
}

object EmuKnobs {

    private fun o(vararg pairs: Pair<String, String>) = pairs.map { EmuOption(it.first, it.second) }
    private val onOff = o("true" to "On", "false" to "Off")
    private val onOffCaps = o("True" to "On", "False" to "Off")

    // ---- Eden: config/custom/<title ID>.ini, sections as Eden writes them.
    // Values from Eden's settings_enums.h (ResolutionSetup starts at ¼×; 5 = 1½×).

    val EDEN_RESOLUTION = EmuKnob(
        "eden.resolution", Emulator.EDEN, "Resolution", "Renderer", "resolution_setup",
        o("1" to "½× · 360p", "2" to "¾× · 540p", "3" to "1× · 720p", "4" to "1¼× · 900p", "5" to "1½× · 1080p", "6" to "2× · 1440p"),
        hint = "The resolution the game renders at. The screen is 1080p.",
    )
    val EDEN_ACCURACY = EmuKnob(
        "eden.accuracy", Emulator.EDEN, "GPU accuracy", "Renderer", "gpu_accuracy",
        o("0" to "Low · fastest", "1" to "Medium", "2" to "High · fewest glitches"),
        hint = "Higher fixes some effects (lighting, particles) at a real cost in speed.",
    )
    val EDEN_DOCKED = EmuKnob(
        "eden.docked", Emulator.EDEN, "Console mode", "System", "use_docked_mode",
        o("0" to "Handheld", "1" to "Docked"),
        hint = "Docked makes games render for a TV: sharper, and heavier.",
    )
    val EDEN_CPU_ACCURACY = EmuKnob(
        "eden.cpu_accuracy", Emulator.EDEN, "CPU accuracy", "Cpu", "cpu_accuracy",
        o("0" to "Auto", "1" to "Accurate", "2" to "Unsafe · faster", "3" to "Paranoid · slowest"),
        hint = "Unsafe is faster; Accurate fixes the odd game that crashes or misbehaves.",
    )
    val EDEN_ASTC = EmuKnob(
        "eden.astc", Emulator.EDEN, "ASTC texture decoding", "Renderer", "accelerate_astc",
        o("0" to "CPU", "1" to "GPU", "2" to "CPU, in the background"),
        hint = "How compressed textures are unpacked. GPU is usually fastest on this chip.",
    )
    val EDEN_ASTC_RECOMPRESSION = EmuKnob(
        "eden.astc_recompression", Emulator.EDEN, "ASTC recompression", "Renderer", "astc_recompression",
        o("0" to "Off", "1" to "BC1 · smallest", "2" to "BC3"),
        hint = "Saves graphics memory in texture-heavy games, at some quality.", advanced = true,
    )
    val EDEN_ASYNC_SHADERS = EmuKnob(
        "eden.async_shaders", Emulator.EDEN, "Asynchronous shaders", "Renderer", "use_asynchronous_shaders",
        onOff, hint = "Less stutter while shaders build, with brief missing effects instead.",
    )
    val EDEN_CPU_CLOCK = EmuKnob(
        "eden.cpu_clock", Emulator.EDEN, "CPU clock", "Cpu", "fast_cpu_time",
        o("0" to "Off", "1" to "Boost", "2" to "Fast"),
        hint = "Runs the Switch's CPU faster: helps games that dip, may break timing in some.", advanced = true,
    )
    val EDEN_GPU_CLOCK = EmuKnob(
        "eden.gpu_clock", Emulator.EDEN, "GPU clock", "Renderer", "fast_gpu_time",
        o("0" to "Off", "1" to "Boost", "2" to "Fast"),
        hint = "Tells games the GPU is faster, which can steady the frame rate.", advanced = true,
    )
    val EDEN_ANISOTROPY = EmuKnob(
        "eden.anisotropy", Emulator.EDEN, "Anisotropic filtering", "Renderer", "max_anisotropy",
        o("0" to "Automatic", "1" to "Default", "2" to "2×", "3" to "4×", "4" to "8×", "5" to "16×"),
        advanced = true,
    )
    val EDEN_VSYNC = EmuKnob(
        "eden.vsync", Emulator.EDEN, "VSync", "Renderer", "use_vsync",
        o("0" to "Off (Immediate)", "1" to "Mailbox", "2" to "On (FIFO)", "3" to "FIFO relaxed"),
        advanced = true,
    )
    val EDEN_FRAME_PACING = EmuKnob(
        "eden.frame_pacing", Emulator.EDEN, "Frame pacing", "Renderer", "frame_pacing_mode",
        o("0" to "Auto", "1" to "30 fps", "2" to "60 fps", "3" to "90 fps", "4" to "120 fps"),
        advanced = true,
    )
    val EDEN_DMA_ACCURACY = EmuKnob(
        "eden.dma_accuracy", Emulator.EDEN, "DMA accuracy", "Renderer", "dma_accuracy",
        o("0" to "Default", "1" to "Unsafe · faster", "2" to "Safe"),
        advanced = true,
    )
    val EDEN_REACTIVE_FLUSHING = EmuKnob(
        "eden.reactive_flushing", Emulator.EDEN, "Reactive flushing", "Renderer", "use_reactive_flushing",
        onOff, hint = "Fixes missing shadows or effects in some games; costs speed.", advanced = true,
    )
    val EDEN_NVDEC = EmuKnob(
        "eden.nvdec", Emulator.EDEN, "Video decoding", "Renderer", "nvdec_emulation",
        o("0" to "Off", "1" to "CPU", "2" to "GPU"),
        hint = "For in-game videos. Off skips them.", advanced = true,
    )
    val EDEN_VRAM = EmuKnob(
        "eden.vram", Emulator.EDEN, "VRAM usage", "Renderer", "vram_usage_mode",
        o("0" to "Conservative", "1" to "Aggressive"),
        advanced = true,
    )
    val EDEN_MEMORY = EmuKnob(
        "eden.memory", Emulator.EDEN, "Memory layout", "Core", "memory_layout_mode",
        o("0" to "4 GB", "1" to "6 GB", "2" to "8 GB"),
        hint = "More than 4 GB is only for mods that need it.", advanced = true,
    )
    val EDEN_BLOOM = EmuKnob(
        "eden.fix_bloom", Emulator.EDEN, "Fix bloom effects", "Renderer", "fix_bloom_effects",
        onOff, advanced = true,
    )
    val EDEN_RESCALE_HACK = EmuKnob(
        "eden.rescale_hack", Emulator.EDEN, "Legacy rescale pass", "Renderer", "rescale_hack",
        onOff, hint = "Fixes lines or seams some games show when upscaled.", advanced = true,
    )
    val EDEN_FORCE_MAX_CLOCK = EmuKnob(
        "eden.force_max_clock", Emulator.EDEN, "Force maximum GPU clocks", "Renderer", "force_max_clock",
        onOff, hint = "Keeps the GPU at full speed: steadier frame rate, more heat and battery.", advanced = true,
    )
    val EDEN_DISK_CACHE = EmuKnob(
        "eden.disk_shader_cache", Emulator.EDEN, "Disk shader cache", "Renderer", "use_disk_shader_cache",
        onOff, hint = "Keeps built shaders between sessions, so there's less stutter next time.", advanced = true,
    )
    val EDEN_ANTI_ALIASING = EmuKnob(
        "eden.anti_aliasing", Emulator.EDEN, "Anti-aliasing", "Renderer", "anti_aliasing",
        o("0" to "None", "1" to "FXAA", "2" to "SMAA"),
        advanced = true,
    )

    /** Its options are the drivers installed in Eden (see [edenDriver]). */
    val EDEN_DRIVER = EmuKnob("eden.driver", Emulator.EDEN, "GPU driver", "GpuDriver", "driver_path", emptyList())

    // ---- Dolphin: GameSettings/<game ID>.ini, loaded over Dolphin's own fixes for the game.
    // Global values: GFX.ini [Settings]/[Enhancements]/[Hacks] and Dolphin.ini [Core].

    val DOLPHIN_RESOLUTION = EmuKnob(
        "dolphin.resolution", Emulator.DOLPHIN, "Resolution", "Video_Settings", "InternalResolution",
        o("1" to "1× · 480p", "2" to "2× · for 720p", "3" to "3× · for 1080p", "4" to "4× · for 1440p"),
        default = "1", globalFile = "GFX.ini", globalSection = "Settings",
    )
    val DOLPHIN_SHADERS = EmuKnob(
        "dolphin.shader_mode", Emulator.DOLPHIN, "Shader compilation", "Video_Settings", "ShaderCompilationMode",
        o("0" to "Specialized (stutters)", "1" to "Exclusive ubershaders", "2" to "Hybrid ubershaders", "3" to "Skip drawing"),
        hint = "How new effects are built. Hybrid avoids most stutter; Skip drawing is lightest.",
        default = "0", globalFile = "GFX.ini", globalSection = "Settings",
    )
    val DOLPHIN_WAIT_SHADERS = EmuKnob(
        "dolphin.wait_shaders", Emulator.DOLPHIN, "Compile shaders before starting", "Video_Settings", "WaitForShadersBeforeStarting",
        onOffCaps, default = "False", globalFile = "GFX.ini", globalSection = "Settings", advanced = true,
    )
    val DOLPHIN_EFB_ACCESS = EmuKnob(
        "dolphin.efb_access", Emulator.DOLPHIN, "Skip EFB access from CPU", "Video_Hacks", "EFBAccessEnable",
        o("False" to "Skip · faster", "True" to "Don't skip · accurate"),
        hint = "Some games need it (lens flares, cursors, some physics).",
        default = "False", globalFile = "GFX.ini", globalSection = "Hacks",
    )
    val DOLPHIN_EFB_TEXTURE = EmuKnob(
        "dolphin.efb_to_texture", Emulator.DOLPHIN, "Store EFB copies to texture only", "Video_Hacks", "EFBToTextureEnable",
        o("True" to "On · faster", "False" to "Off · accurate"),
        hint = "Off fixes effects some games draw into memory (heat haze, scanners).",
        default = "True", globalFile = "GFX.ini", globalSection = "Hacks",
    )
    val DOLPHIN_XFB_TEXTURE = EmuKnob(
        "dolphin.xfb_to_texture", Emulator.DOLPHIN, "Store XFB copies to texture only", "Video_Hacks", "XFBToTextureEnable",
        o("True" to "On · faster", "False" to "Off · accurate"),
        default = "True", globalFile = "GFX.ini", globalSection = "Hacks", advanced = true,
    )
    val DOLPHIN_DEFER_EFB = EmuKnob(
        "dolphin.defer_efb", Emulator.DOLPHIN, "Defer EFB copies to RAM", "Video_Hacks", "DeferEFBCopies",
        o("True" to "On · faster", "False" to "Off · accurate"),
        default = "True", globalFile = "GFX.ini", globalSection = "Hacks", advanced = true,
    )
    val DOLPHIN_EFB_SCALED = EmuKnob(
        "dolphin.efb_scaled", Emulator.DOLPHIN, "Scaled EFB copy", "Video_Hacks", "EFBScaledCopy",
        onOffCaps, default = "True", globalFile = "GFX.ini", globalSection = "Hacks", advanced = true,
    )
    val DOLPHIN_BBOX = EmuKnob(
        "dolphin.bbox", Emulator.DOLPHIN, "Bounding box", "Video_Hacks", "BBoxEnable",
        onOffCaps, hint = "Needed by a few games (Paper Mario, Super Paper Mario).",
        default = "False", globalFile = "GFX.ini", globalSection = "Hacks", advanced = true,
    )
    val DOLPHIN_VERTEX_ROUNDING = EmuKnob(
        "dolphin.vertex_rounding", Emulator.DOLPHIN, "Vertex rounding", "Video_Hacks", "VertexRounding",
        onOffCaps, hint = "Fixes lines in some 2D games when upscaled.",
        default = "False", globalFile = "GFX.ini", globalSection = "Hacks", advanced = true,
    )
    val DOLPHIN_TEXTURE_CACHE = EmuKnob(
        "dolphin.texture_cache", Emulator.DOLPHIN, "Texture cache accuracy", "Video_Settings", "SafeTextureCacheColorSamples",
        o("0" to "Safe", "512" to "Medium", "128" to "Fast"),
        hint = "Safe fixes wrong textures in a few games.",
        default = "128", globalFile = "GFX.ini", globalSection = "Settings", advanced = true,
    )
    val DOLPHIN_ANISOTROPY = EmuKnob(
        "dolphin.anisotropy", Emulator.DOLPHIN, "Anisotropic filtering", "Video_Enhancements", "MaxAnisotropy",
        o("-1" to "Game default", "0" to "1×", "1" to "2×", "2" to "4×", "3" to "8×", "4" to "16×"),
        default = "-1", globalFile = "GFX.ini", globalSection = "Enhancements", advanced = true,
    )
    val DOLPHIN_WIDESCREEN = EmuKnob(
        "dolphin.widescreen_hack", Emulator.DOLPHIN, "Widescreen hack", "Video_Settings", "wideScreenHack",
        onOffCaps, hint = "Stretches 4:3 games to 16:9 by widening the view; may show pop-in at the edges.",
        default = "False", globalFile = "GFX.ini", globalSection = "Settings", advanced = true,
    )
    val DOLPHIN_DUAL_CORE = EmuKnob(
        "dolphin.dual_core", Emulator.DOLPHIN, "Dual core", "Core", "CPUThread",
        onOffCaps, hint = "Much faster; a few games need it off to run correctly.",
        default = "True", globalFile = "Dolphin.ini", globalSection = "Core",
    )
    val DOLPHIN_SYNC_GPU = EmuKnob(
        "dolphin.sync_gpu", Emulator.DOLPHIN, "Synchronize GPU thread", "Core", "SyncGPU",
        onOffCaps, hint = "Fixes random crashes some games have with dual core.",
        default = "False", globalFile = "Dolphin.ini", globalSection = "Core", advanced = true,
    )
    val DOLPHIN_OVERCLOCK_ON = EmuKnob(
        "dolphin.overclock_on", Emulator.DOLPHIN, "CPU clock override", "Core", "OverclockEnable",
        onOffCaps, hint = "Underclocking helps heavy games keep speed; overclocking smooths games that dip on console.",
        default = "False", globalFile = "Dolphin.ini", globalSection = "Core", advanced = true,
    )
    val DOLPHIN_OVERCLOCK = EmuKnob(
        "dolphin.overclock", Emulator.DOLPHIN, "CPU clock", "Core", "Overclock",
        o("0.5" to "50%", "0.75" to "75%", "0.9" to "90%", "1.0" to "100%", "1.5" to "150%", "2.0" to "200%"),
        hint = "Takes effect with the CPU clock override on.",
        default = "1.0", globalFile = "Dolphin.ini", globalSection = "Core", advanced = true,
    )

    // ---- Azahar: config.ini keys, swapped in by the ES-DE hook. Values from Azahar's settings.

    val AZAHAR_RESOLUTION = EmuKnob(
        "azahar.resolution", Emulator.AZAHAR, "Resolution", "Renderer", "resolution_factor",
        o("1" to "1× · 240p", "2" to "2× · 480p", "3" to "3× · 720p", "4" to "4× · 960p", "5" to "5× · for 1080p"),
    )
    val AZAHAR_CPU_CLOCK = EmuKnob(
        "azahar.cpu_clock", Emulator.AZAHAR, "CPU clock", "Core", "cpu_clock_percentage",
        o("50" to "50%", "75" to "75%", "100" to "100%", "125" to "125%", "150" to "150%", "200" to "200%"),
        hint = "Lower helps heavy games keep speed (they may freeze); higher smooths games that lag on a real 3DS.",
        default = "100",
    )
    val AZAHAR_ACCURATE_MUL = EmuKnob(
        "azahar.accurate_mul", Emulator.AZAHAR, "Accurate multiplication", "Renderer", "shaders_accurate_mul",
        onOff, hint = "Fixes wrong lighting or outlines in some games; a little slower.",
        default = "false",
    )
    val AZAHAR_ASYNC_SHADERS = EmuKnob(
        "azahar.async_shaders", Emulator.AZAHAR, "Asynchronous shaders", "Renderer", "async_shader_compilation",
        onOff, default = "true", advanced = true,
    )
    val AZAHAR_HW_SHADER = EmuKnob(
        "azahar.hw_shader", Emulator.AZAHAR, "Hardware shaders", "Renderer", "use_hw_shader",
        onOff, default = "true", advanced = true,
    )
    val AZAHAR_TEXTURE_FILTER = EmuKnob(
        "azahar.texture_filter", Emulator.AZAHAR, "Texture filter", "Renderer", "texture_filter",
        o("0" to "None", "1" to "Anime4K", "2" to "Bicubic", "3" to "ScaleForce", "4" to "xBRZ", "5" to "MMPX"),
        default = "0", advanced = true,
    )
    val AZAHAR_TEXTURE_SAMPLING = EmuKnob(
        "azahar.texture_sampling", Emulator.AZAHAR, "Texture sampling", "Renderer", "texture_sampling",
        o("0" to "Game controlled", "1" to "Nearest", "2" to "Linear"),
        default = "0", advanced = true,
    )
    val AZAHAR_LAYOUT = EmuKnob(
        "azahar.layout", Emulator.AZAHAR, "Screen layout", "Layout", "layout_option",
        o("0" to "Original", "1" to "Single screen", "2" to "Large screen", "3" to "Side by side", "4" to "Hybrid"),
        default = "2", advanced = true,
    )

    // ---- ARMSX2: gamesettings/<serial>_<CRC>.ini, which PCSX2's core reads over ARMSX2's settings.
    // Keys as ARMSX2 writes them in PCSX2-Android.ini.

    val ARMSX2_UPSCALE = EmuKnob(
        "armsx2.upscale", Emulator.ARMSX2, "Resolution", "EmuCore/GS", "upscale_multiplier",
        o("1" to "1× · native", "2" to "2× · 720p", "3" to "3× · for 1080p", "4" to "4× · 1440p"),
    )
    val ARMSX2_EE_RATE = EmuKnob(
        "armsx2.ee_cycle_rate", Emulator.ARMSX2, "EE cycle rate", "EmuCore/Speedhacks", "EECycleRate",
        o("-3" to "50%", "-2" to "60%", "-1" to "75%", "0" to "100%", "1" to "130%", "2" to "180%", "3" to "300%"),
        hint = "Underclocking the PS2's main CPU speeds up heavy games (may cause slowdowns in-game).",
        default = "0",
    )
    val ARMSX2_EE_SKIP = EmuKnob(
        "armsx2.ee_cycle_skip", Emulator.ARMSX2, "EE cycle skip", "EmuCore/Speedhacks", "EECycleSkip",
        o("0" to "Off", "1" to "Mild", "2" to "Moderate", "3" to "Maximum"),
        default = "0", advanced = true,
    )
    val ARMSX2_MTVU = EmuKnob(
        "armsx2.mtvu", Emulator.ARMSX2, "MTVU (VU1 on its own thread)", "EmuCore/Speedhacks", "vuThread",
        onOff, hint = "Faster in most games; a few need it off.", default = "true", advanced = true,
    )
    val ARMSX2_INSTANT_VU1 = EmuKnob(
        "armsx2.instant_vu1", Emulator.ARMSX2, "Instant VU1", "EmuCore/Speedhacks", "vu1Instant",
        onOff, default = "true", advanced = true,
    )
    val ARMSX2_BLENDING = EmuKnob(
        "armsx2.blending", Emulator.ARMSX2, "Blending accuracy", "EmuCore/GS", "accurate_blending_unit",
        o("0" to "Minimum", "1" to "Basic", "2" to "Medium", "3" to "High"),
        hint = "Higher fixes transparency effects at a big cost on phones.", default = "1",
    )
    val ARMSX2_HW_DOWNLOAD = EmuKnob(
        "armsx2.hw_download", Emulator.ARMSX2, "Hardware download mode", "EmuCore/GS", "HWDownloadMode",
        o("0" to "Accurate", "1" to "No readbacks", "2" to "Unsynchronized", "3" to "Disabled"),
        hint = "Skipping readbacks speeds up some games; can break effects.", default = "0", advanced = true,
    )
    val ARMSX2_FILTER = EmuKnob(
        "armsx2.texture_filter", Emulator.ARMSX2, "Texture filtering", "EmuCore/GS", "filter",
        o("0" to "Nearest", "1" to "Bilinear (forced)", "2" to "Bilinear (PS2)", "3" to "Bilinear (forced, not sprites)"),
        default = "2", advanced = true,
    )
    val ARMSX2_USER_HACKS = EmuKnob(
        "armsx2.user_hacks", Emulator.ARMSX2, "Manual hardware fixes", "EmuCore/GS", "UserHacks",
        onOff, hint = "Needed for the upscaling fixes below to take effect.", default = "false", advanced = true,
    )
    val ARMSX2_HALF_PIXEL = EmuKnob(
        "armsx2.half_pixel", Emulator.ARMSX2, "Half-pixel offset", "EmuCore/GS", "UserHacks_HalfPixelOffset",
        o("0" to "Off", "1" to "Normal (vertex)", "2" to "Special (texture)", "3" to "Special (texture, aggressive)", "4" to "Align to native", "5" to "Align to native with texture offset"),
        hint = "Fixes blur and misaligned effects when upscaled.", default = "0", advanced = true,
    )
    val ARMSX2_ROUND_SPRITE = EmuKnob(
        "armsx2.round_sprite", Emulator.ARMSX2, "Round sprite", "EmuCore/GS", "UserHacks_round_sprite_offset",
        o("0" to "Off", "1" to "Half", "2" to "Full"),
        hint = "Fixes lines in 2D elements when upscaled.", default = "0", advanced = true,
    )

    // ---- DuckStation: gamesettings/<serial>.ini, over DuckStation's settings.

    val DUCKSTATION_RESOLUTION = EmuKnob(
        "duckstation.resolution", Emulator.DUCKSTATION, "Resolution", "GPU", "ResolutionScale",
        o("1" to "1× · native", "2" to "2×", "3" to "3× · 720p", "4" to "4×", "5" to "5× · for 1080p"),
    )
    val DUCKSTATION_WIDESCREEN = EmuKnob(
        "duckstation.widescreen", Emulator.DUCKSTATION, "Widescreen rendering", "GPU", "WidescreenHack",
        onOff, hint = "Draws 3D games in 16:9 (pair with a 16:9 aspect ratio).", default = "false",
    )
    val DUCKSTATION_ASPECT = EmuKnob(
        "duckstation.aspect", Emulator.DUCKSTATION, "Aspect ratio", "Display", "AspectRatio",
        o("Auto (Game Native)" to "Game's own", "4:3" to "4:3", "16:9" to "16:9", "Stretch To Fill" to "Stretch"),
        default = "Auto (Game Native)",
    )
    val DUCKSTATION_PGXP = EmuKnob(
        "duckstation.pgxp", Emulator.DUCKSTATION, "PGXP geometry correction", "GPU", "PGXPEnable",
        onOff, hint = "Stops the PS1's wobbly polygons.", default = "false", advanced = true,
    )
    val DUCKSTATION_PGXP_TEXTURE = EmuKnob(
        "duckstation.pgxp_texture", Emulator.DUCKSTATION, "PGXP texture correction", "GPU", "PGXPTextureCorrection",
        onOff, default = "true", advanced = true,
    )
    val DUCKSTATION_OVERCLOCK = EmuKnob(
        "duckstation.overclock", Emulator.DUCKSTATION, "CPU overclock", "CPU", "OverclockEnable",
        onOff, hint = "Smooths games that dropped frames on a real PS1; can break some.", default = "false", advanced = true,
    )

    // ---- RetroArch cores: config/<core>/<game>.opt, RetroArch's own per-game core options.
    // Keys and values from each core's libretro_core_options.h.

    private fun swan(key: String, title: String, name: String, options: List<EmuOption>, default: String, hint: String? = null, advanced: Boolean = false) =
        EmuKnob("swanstation.$key", Emulator.SWANSTATION, title, "", "swanstation_$name", options, hint, advanced, default)

    val SWAN_RESOLUTION = swan(
        "resolution", "Resolution", "GPU_ResolutionScale",
        o("1" to "1× · native", "2" to "2×", "3" to "3× · 720p", "4" to "4× · 960p", "5" to "5× · 1200p", "6" to "6×"),
        "1", hint = "The resolution 3D is drawn at. The screen is 1080p.",
    )
    val SWAN_WIDESCREEN = swan(
        "widescreen", "Widescreen rendering", "GPU_WidescreenHack", onOff, "false",
        hint = "Draws 3D games in 16:9 (pair with a 16:9 aspect ratio). 2D parts can stretch.",
    )
    val SWAN_ASPECT = swan(
        "aspect", "Aspect ratio", "Display_AspectRatio",
        o("4:3" to "4:3", "Auto" to "Game's own", "16:9" to "16:9"), "4:3",
    )
    val SWAN_PGXP = swan(
        "pgxp", "PGXP geometry correction", "GPU_PGXPEnable", onOff, "false",
        hint = "Stops the PS1's wobbly polygons and warping textures.",
    )
    val SWAN_PGXP_TEXTURE = swan("pgxp_texture", "PGXP texture correction", "GPU_PGXPTextureCorrection", onOff, "true", advanced = true)
    val SWAN_PGXP_DEPTH = swan(
        "pgxp_depth", "PGXP depth buffer", "GPU_PGXPDepthBuffer", onOff, "false",
        hint = "Fixes polygons drawn in front of each other in the wrong order; breaks some games.", advanced = true,
    )
    val SWAN_TRUE_COLOR = swan(
        "true_color", "True color", "GPU_TrueColor", onOff, "false",
        hint = "Renders in 24-bit color: smooth gradients instead of dithered bands.", advanced = true,
    )
    val SWAN_TEXTURE_FILTER = swan(
        "texture_filter", "Texture filtering", "GPU_TextureFilter",
        o("Nearest" to "Off (sharp)", "Bilinear" to "Bilinear", "JINC2" to "JINC2", "xBR" to "xBR"), "Nearest", advanced = true,
    )
    val SWAN_OVERCLOCK = swan(
        "overclock", "CPU clock", "CPU_Overclock",
        o("100" to "100% · PS1", "125" to "125%", "150" to "150%", "200" to "200%"), "100",
        hint = "Smooths games that dropped frames on a real PS1; can break some.", advanced = true,
    )
    val SWAN_RENDERER = swan(
        "renderer", "Renderer", "GPU_Renderer",
        o("Auto" to "Auto", "Vulkan" to "Vulkan", "OpenGL" to "OpenGL", "Software" to "Software"), "Auto",
        hint = "Software is the most accurate but ignores the resolution.", advanced = true,
    )

    private fun mupen(key: String, title: String, name: String, options: List<EmuOption>, default: String?, hint: String? = null, advanced: Boolean = false) =
        EmuKnob("mupen64.$key", Emulator.MUPEN64, title, "", "mupen64plus-$name", options, hint, advanced, default)

    val MUPEN_RESOLUTION = mupen(
        "resolution", "Resolution", "43screensize",
        o("640x480" to "640×480 · 2×", "960x720" to "960×720 · 3×", "1280x960" to "1280×960 · 4×", "1440x1080" to "1440×1080 · 1080p", "1920x1440" to "1920×1440 · 6×"),
        "640x480", hint = "The resolution the game renders at (GLideN64). The screen is 1080p.",
    )
    val MUPEN_ASPECT = mupen(
        "aspect", "Aspect ratio", "aspect",
        o("4:3" to "4:3", "16:9 adjusted" to "16:9 (widescreen hack)", "16:9" to "16:9 (stretched)"), "4:3",
        hint = "\"Widescreen hack\" shows more of the scene; menus and 2D can look off.",
    )
    val MUPEN_RDP = mupen(
        "rdp", "Graphics plugin", "rdp-plugin",
        o("gliden64" to "GLideN64", "parallel" to "ParaLLEl (Vulkan)", "angrylion" to "Angrylion (software)"), "gliden64",
        hint = "GLideN64 is fastest; ParaLLEl is accurate and needs RetroArch's Vulkan driver.", advanced = true,
    )
    val MUPEN_FB = mupen(
        "framebuffer", "Framebuffer emulation", "EnableFBEmulation", onOffCaps, "True",
        hint = "Needed for many effects; off is faster but breaks them.", advanced = true,
    )
    val MUPEN_COPY_COLOR = mupen(
        "copy_color", "Color buffer to RDRAM", "EnableCopyColorToRDRAM",
        o("Off" to "Off", "Sync" to "Sync", "Async" to "Async", "TripleBuffer" to "Triple buffer"), "Async",
        hint = "Fixes effects the game reads back (Pokémon Snap's photos, pause-screen backgrounds).", advanced = true,
    )
    val MUPEN_COPY_DEPTH = mupen(
        "copy_depth", "Depth buffer to RDRAM", "EnableCopyDepthToRDRAM",
        o("Off" to "Off", "Software" to "Software", "FromMem" to "From memory"), "Software",
        hint = "Fixes effects that test depth (Zelda's sun and Lens of Truth, coronas).", advanced = true,
    )
    val MUPEN_CPU = mupen(
        "cpu", "CPU core", "cpucore",
        o("dynamic_recompiler" to "Dynarec", "cached_interpreter" to "Cached interpreter", "pure_interpreter" to "Pure interpreter"), "dynamic_recompiler",
        hint = "The interpreters fix a few games that crash, and are slower.", advanced = true,
    )
    val MUPEN_COUNT_PER_OP = mupen(
        "count_per_op", "Count per op", "CountPerOp",
        o("0" to "Game's own", "1" to "1 · fastest CPU", "2" to "2", "3" to "3"), "0",
        hint = "Lower numbers run the N64's CPU faster, smoothing some games; can break timing.", advanced = true,
    )
    val MUPEN_VI_REFRESH = mupen(
        "vi_refresh", "VI refresh (overclock)", "virefresh",
        o("Auto" to "Auto", "1500" to "1500", "2200" to "2200"), "Auto",
        hint = "Raises the frame rate cap in games limited by the N64's video timing.", advanced = true,
    )
    val MUPEN_MSAA = mupen(
        "msaa", "Anti-aliasing (MSAA)", "MultiSampling",
        o("0" to "Off", "2" to "2×", "4" to "4×"), "0", advanced = true,
    )

    private fun fly(key: String, title: String, name: String, options: List<EmuOption>, default: String?, hint: String? = null, advanced: Boolean = false) =
        EmuKnob("flycast.$key", Emulator.FLYCAST, title, "", "reicast_$name", options, hint, advanced, default)

    private val enabledDisabled = o("enabled" to "On", "disabled" to "Off")

    val FLY_RESOLUTION = fly(
        "resolution", "Resolution", "internal_resolution",
        o("640x480" to "640×480 · native", "1280x960" to "1280×960 · 2×", "1440x1080" to "1440×1080 · 1080p", "1920x1440" to "1920×1440 · 3×", "2560x1920" to "2560×1920 · 4×"),
        "640x480", hint = "The resolution the game renders at. The screen is 1080p.",
    )
    val FLY_WIDESCREEN_CHEATS = fly(
        "widescreen_cheats", "Widescreen cheats", "widescreen_cheats", enabledDisabled, "disabled",
        hint = "Real 16:9 for games that have a widescreen patch; the cleanest widescreen.",
    )
    val FLY_WIDESCREEN = fly(
        "widescreen", "Widescreen hack", "widescreen_hack", enabledDisabled, "disabled",
        hint = "16:9 for any game; objects can pop in at the edges.", advanced = true,
    )
    val FLY_ALPHA = fly(
        "alpha_sorting", "Transparency sorting", "alpha_sorting",
        o("per-triangle (normal)" to "Per triangle", "per-pixel (accurate)" to "Per pixel (accurate)"), "per-triangle (normal)",
        hint = "Per pixel fixes transparent objects drawn in the wrong order; heavier.", advanced = true,
    )
    val FLY_RTTB = fly(
        "rttb", "Render-to-texture buffer", "enable_rttb", enabledDisabled, "disabled",
        hint = "Fixes effects drawn into textures (some menus, mirrors, screens in screens).", advanced = true,
    )
    val FLY_FRAMEBUFFER = fly(
        "framebuffer", "Full framebuffer emulation", "emulate_framebuffer", enabledDisabled, "disabled",
        hint = "Needed by a few games' effects; heavy, and ignores the resolution.", advanced = true,
    )
    val FLY_SKIP = fly(
        "auto_skip", "Auto frame skip", "auto_skip_frame",
        o("disabled" to "Off", "some" to "Some", "more" to "More"), "disabled",
        hint = "Skips frames when the game can't keep up.", advanced = true,
    )
    val FLY_THREADED = fly(
        "threaded", "Threaded rendering", "threaded_rendering", enabledDisabled, "enabled",
        hint = "Faster; off fixes timing problems in a few games.", advanced = true,
    )
    val FLY_DELAY_SWAP = fly(
        "delay_swap", "Delay frame swapping", "delay_frame_swapping", enabledDisabled, "enabled",
        hint = "Stops flicker and black screens in some games' menus and videos.", advanced = true,
    )

    val all = listOf(
        EDEN_RESOLUTION, EDEN_ACCURACY, EDEN_DOCKED, EDEN_CPU_ACCURACY, EDEN_ASTC, EDEN_ASYNC_SHADERS, EDEN_DRIVER,
        EDEN_ASTC_RECOMPRESSION, EDEN_CPU_CLOCK, EDEN_GPU_CLOCK, EDEN_ANISOTROPY, EDEN_VSYNC, EDEN_FRAME_PACING,
        EDEN_DMA_ACCURACY, EDEN_REACTIVE_FLUSHING, EDEN_NVDEC, EDEN_VRAM, EDEN_MEMORY, EDEN_BLOOM, EDEN_RESCALE_HACK,
        EDEN_ANTI_ALIASING, EDEN_FORCE_MAX_CLOCK, EDEN_DISK_CACHE,
        DOLPHIN_RESOLUTION, DOLPHIN_SHADERS, DOLPHIN_EFB_ACCESS, DOLPHIN_EFB_TEXTURE, DOLPHIN_DUAL_CORE,
        DOLPHIN_WAIT_SHADERS, DOLPHIN_XFB_TEXTURE, DOLPHIN_DEFER_EFB, DOLPHIN_EFB_SCALED, DOLPHIN_BBOX,
        DOLPHIN_VERTEX_ROUNDING, DOLPHIN_TEXTURE_CACHE, DOLPHIN_ANISOTROPY, DOLPHIN_WIDESCREEN, DOLPHIN_SYNC_GPU,
        DOLPHIN_OVERCLOCK_ON, DOLPHIN_OVERCLOCK,
        AZAHAR_RESOLUTION, AZAHAR_CPU_CLOCK, AZAHAR_ACCURATE_MUL, AZAHAR_ASYNC_SHADERS, AZAHAR_HW_SHADER,
        AZAHAR_TEXTURE_FILTER, AZAHAR_TEXTURE_SAMPLING, AZAHAR_LAYOUT,
        ARMSX2_UPSCALE, ARMSX2_EE_RATE, ARMSX2_BLENDING, ARMSX2_EE_SKIP, ARMSX2_MTVU, ARMSX2_INSTANT_VU1,
        ARMSX2_HW_DOWNLOAD, ARMSX2_FILTER, ARMSX2_USER_HACKS, ARMSX2_HALF_PIXEL, ARMSX2_ROUND_SPRITE,
        DUCKSTATION_RESOLUTION, DUCKSTATION_WIDESCREEN, DUCKSTATION_ASPECT, DUCKSTATION_PGXP,
        DUCKSTATION_PGXP_TEXTURE, DUCKSTATION_OVERCLOCK,
        SWAN_RESOLUTION, SWAN_WIDESCREEN, SWAN_ASPECT, SWAN_PGXP, SWAN_PGXP_TEXTURE, SWAN_PGXP_DEPTH, SWAN_TRUE_COLOR,
        SWAN_TEXTURE_FILTER, SWAN_OVERCLOCK, SWAN_RENDERER,
        MUPEN_RESOLUTION, MUPEN_ASPECT, MUPEN_RDP, MUPEN_FB, MUPEN_COPY_COLOR, MUPEN_COPY_DEPTH, MUPEN_CPU,
        MUPEN_COUNT_PER_OP, MUPEN_VI_REFRESH, MUPEN_MSAA,
        FLY_RESOLUTION, FLY_WIDESCREEN_CHEATS, FLY_WIDESCREEN, FLY_ALPHA, FLY_RTTB, FLY_FRAMEBUFFER, FLY_SKIP,
        FLY_THREADED, FLY_DELAY_SWAP,
    )

    /**
     * The same setting in the other PS1 emulator, for a game whose settings
     * were picked for DuckStation and that now runs in SwanStation (or back).
     */
    private val PS1_TWINS = mapOf(
        DUCKSTATION_RESOLUTION to SWAN_RESOLUTION, DUCKSTATION_WIDESCREEN to SWAN_WIDESCREEN,
        DUCKSTATION_ASPECT to SWAN_ASPECT, DUCKSTATION_PGXP to SWAN_PGXP, DUCKSTATION_PGXP_TEXTURE to SWAN_PGXP_TEXTURE,
    )
    private val ASPECT_TWINS = mapOf("Auto (Game Native)" to "Auto", "4:3" to "4:3", "16:9" to "16:9")

    /**
     * [emu] (a game's emulator settings) for [emulator]: its own settings,
     * plus ones set for the other PS1 emulator where it has the same setting
     * and none of its own. Settings for other emulators are left out.
     */
    fun forEmulator(emu: Map<String, String>, emulator: Emulator?): Map<String, String> {
        if (emulator == null) return emu
        val own = emu.filter { byKey(it.key)?.emulator == emulator }.toMutableMap()
        for ((duck, swan) in PS1_TWINS) {
            val (from, to) = when (emulator) {
                Emulator.SWANSTATION -> duck to swan
                Emulator.DUCKSTATION -> swan to duck
                else -> continue
            }
            if (to.key in own) continue
            val value = emu[from.key] ?: continue
            val translated = when {
                value == Suggestions.GLOBAL -> value
                from == DUCKSTATION_ASPECT -> ASPECT_TWINS[value]
                to == DUCKSTATION_ASPECT -> ASPECT_TWINS.entries.firstOrNull { it.value == value }?.key
                else -> value
            } ?: continue
            if (translated == Suggestions.GLOBAL || to.options.any { it.value == translated }) own[to.key] = translated
        }
        return own
    }

    fun byKey(key: String) = all.firstOrNull { it.key == key }

    fun of(emulator: Emulator) = all.filter { it.emulator == emulator }

    /** The driver choice with Eden's installed drivers ([paths]) as options, plus the system's own. */
    fun edenDriver(paths: List<String>) = EDEN_DRIVER.copy(
        options = listOf(EmuOption("", "System driver")) + paths.map { path ->
            EmuOption(path, path.substringAfterLast('/').removeSuffix(".zip").removeSuffix(".adpkg"))
        },
    )
}

/**
 * Editing emulator ini files a line at a time, so everything else in them
 * (and the emulator's own layout) stays as it was.
 *
 * Eden (a Qt ini) keeps a per-game value as three lines,
 * `name\use_global=false`, `name\default=false` and `name=value`;
 * `name\use_global=true` alone means "same as the global setting".
 * Dolphin writes `Name = value`.
 */
object Ini {

    /** [section]'s lines as a range in [lines]: from its header to the next one. */
    private fun range(lines: List<String>, section: String): IntRange? {
        val start = lines.indexOfFirst { it.trim() == "[$section]" }
        if (start < 0) return null
        val next = (start + 1 until lines.size).firstOrNull { lines[it].trimStart().startsWith("[") } ?: lines.size
        return start until next
    }

    private fun keyOf(line: String): String = line.substringBefore('=').trim()

    /** The value of [name] in [section], if it's set there. */
    fun get(text: String, section: String, name: String): String? {
        val lines = text.lines()
        val r = range(lines, section) ?: return null
        return r.drop(1).map { lines[it] }.firstOrNull { keyOf(it) == name }?.substringAfter('=')?.trim()
    }

    /** Eden: whether [name] has a per-game value of its own. */
    fun edenCustom(text: String, section: String, name: String): Boolean =
        get(text, section, "$name\\use_global") == "false"

    /** Eden: [text] with [name] set to [value] for this game. */
    fun edenSet(text: String, section: String, name: String, value: String): String {
        val block = listOf("$name\\use_global=false", "$name\\default=false", "$name=$value")
        return replaceKey(text, section, setOf(name, "$name\\use_global", "$name\\default"), block)
    }

    /** Eden: [text] with [name] back to the global setting. */
    fun edenClear(text: String, section: String, name: String): String =
        replaceKey(text, section, setOf(name, "$name\\use_global", "$name\\default"), listOf("$name\\use_global=true"))

    /** Dolphin: [text] with `Name = value` in [section]. */
    fun dolphinSet(text: String, section: String, name: String, value: String): String =
        replaceKey(text, section, setOf(name), listOf("$name = $value"))

    /** Dolphin: [text] without [name]; an emptied section goes too. */
    fun dolphinRemove(text: String, section: String, name: String): String {
        val out = replaceKey(text, section, setOf(name), emptyList())
        val lines = out.lines()
        val r = range(lines, section) ?: return out
        if (r.drop(1).any { lines[it].isNotBlank() }) return out
        return lines.filterIndexed { i, _ -> i !in r }.joinToString("\n").trim('\n').let { if (it.isEmpty()) "" else "$it\n" }
    }

    /**
     * [text] with the lines for [keys] in [section] swapped for [block], at
     * the first of them, or at the end of the section (a new one if need be).
     */
    private fun replaceKey(text: String, section: String, keys: Set<String>, block: List<String>): String {
        val lines = text.lines().toMutableList()
        if (lines.lastOrNull() == "") lines.removeAt(lines.size - 1)
        val r = range(lines, section)
        if (r == null) {
            if (block.isEmpty()) return text
            if (lines.isNotEmpty() && lines.last().isNotBlank()) lines += ""
            lines += "[$section]"
            lines += block
            return lines.joinToString("\n") + "\n"
        }
        val hits = r.drop(1).filter { keyOf(lines[it]) in keys }
        val at = hits.firstOrNull() ?: (r.drop(1).lastOrNull { lines[it].isNotBlank() }?.plus(1) ?: (r.first + 1))
        hits.reversed().forEach { lines.removeAt(it) }
        lines.addAll(at.coerceAtMost(lines.size), block)
        return lines.joinToString("\n") + "\n"
    }
}

/** Reading a game's ID out of its ROM, for the emulators that name per-game files by it. */
object GameIds {

    private val TITLE_ID = Regex("\\b(01[0-9A-Fa-f]{14})\\b")

    /** A Switch title ID in a file name ("Game [0100ABCD12340000].nsp"), as the base game's. */
    fun switchFromName(name: String): String? = TITLE_ID.find(name)?.groupValues?.get(1)?.let(::base)

    /** The base game's title ID: an update's (…800) or DLC's has other low bits. */
    fun base(id: String): String = id.uppercase().dropLast(3) + "000"

    /**
     * A Switch title ID from an NSP: its tickets are named by rights ID, which
     * starts with the title ID. XCIs have no tickets, so they're learned by play.
     */
    fun switchFromNsp(file: File): String? = runCatching {
        RandomAccessFile(file, "r").use { f ->
            val head = ByteArray(16).also { f.readFully(it) }
            if (String(head, 0, 4, Charsets.US_ASCII) != "PFS0") return null
            val count = le32(head, 4)
            val tableSize = le32(head, 8)
            if (count !in 1..4096 || tableSize !in 1..1_000_000) return null
            val table = ByteArray(tableSize).also { f.seek(16L + count * 24L); f.readFully(it) }
            String(table, Charsets.US_ASCII).split('\u0000')
                .firstOrNull { it.endsWith(".tik", ignoreCase = true) && it.length >= 20 }
                ?.take(16)?.takeIf { TITLE_ID.matches(it) }?.let(::base)
        }
    }.getOrNull()

    /** A PS2 game's serial from ARMSX2's recent_games.json, by its ROM file name. */
    fun ps2SerialFromRecent(json: String?, romName: String): String? = runCatching {
        val a = org.json.JSONArray(json ?: return null)
        (0 until a.length()).mapNotNull { a.optJSONObject(it) }.firstOrNull { o ->
            java.net.URLDecoder.decode(o.optString("uri").replace("+", "%2B"), "UTF-8").substringAfterLast('/') == romName
        }?.optString("serial")?.takeIf { it.matches(Regex("[A-Z]{4}-\\d{5}")) }
    }.getOrNull()

    /** Eden's NCA header key, from its prod.keys file (needed to read XCIs). */
    fun headerKey(prodKeys: String?): ByteArray? {
        val text = prodKeys ?: return null
        return Regex("(?m)^\\s*header_key\\s*=\\s*([0-9a-fA-F]{64})\\s*$").find(text)?.groupValues?.get(1)
            ?.chunked(2)?.map { it.toInt(16).toByte() }?.toByteArray()
    }

    /**
     * A Switch title ID from an XCI (or NSP) without tickets: the program
     * NCA's header, decrypted with [headerKey], names the game's program ID.
     * XCI: the root HFS0 at 0x130 → its "secure" partition → the NCAs.
     */
    fun switchFromCart(file: File, headerKey: ByteArray): String? = runCatching {
        RandomAccessFile(file, "r").use { f ->
            val ncas = if (String(bytes(f, 0x100, 4), Charsets.US_ASCII) == "HEAD") {
                val root = le64(bytes(f, 0x130, 8), 0)
                val secure = hfs0(f, root)["secure"] ?: return null
                hfs0(f, secure.first)
            } else {
                pfs0(f)
            }
            for ((name, where) in ncas) {
                if (!name.endsWith(".nca")) continue
                val header = Xts.decrypt(bytes(f, where.first, 0x400), headerKey, sectorSize = 0x200)
                if (String(header, 0x200, 4, Charsets.US_ASCII) != "NCA3") continue
                // Content type 0 is a program; its program ID is the game's (updates share it).
                if (header[0x205].toInt() != 0) continue
                return "%016X".format(le64(header, 0x210)).let(::base)
            }
            null
        }
    }.getOrNull()

    private fun bytes(f: RandomAccessFile, at: Long, n: Int) = ByteArray(n).also { f.seek(at); f.readFully(it) }

    /** An HFS0 partition's files: name → (absolute offset, size). */
    private fun hfs0(f: RandomAccessFile, at: Long): Map<String, Pair<Long, Long>> {
        val head = bytes(f, at, 16)
        if (String(head, 0, 4, Charsets.US_ASCII) != "HFS0") return emptyMap()
        val count = le32(head, 4)
        val tableSize = le32(head, 8)
        if (count !in 1..4096 || tableSize !in 1..1_000_000) return emptyMap()
        val entries = bytes(f, at + 16, count * 0x40)
        val names = bytes(f, at + 16 + count * 0x40L, tableSize)
        val data = at + 16 + count * 0x40L + tableSize
        return (0 until count).associate { i ->
            val e = i * 0x40
            val nameAt = le32(entries, e + 16)
            val end = (nameAt until names.size).firstOrNull { names[it].toInt() == 0 } ?: names.size
            String(names, nameAt, end - nameAt, Charsets.US_ASCII) to (data + le64(entries, e) to le64(entries, e + 8))
        }
    }

    /** An NSP's (PFS0) files: name → (absolute offset, size). */
    private fun pfs0(f: RandomAccessFile): Map<String, Pair<Long, Long>> {
        val head = bytes(f, 0, 16)
        if (String(head, 0, 4, Charsets.US_ASCII) != "PFS0") return emptyMap()
        val count = le32(head, 4)
        val tableSize = le32(head, 8)
        if (count !in 1..4096 || tableSize !in 1..1_000_000) return emptyMap()
        val entries = bytes(f, 16, count * 24)
        val names = bytes(f, 16 + count * 24L, tableSize)
        val data = 16 + count * 24L + tableSize
        return (0 until count).associate { i ->
            val e = i * 24
            val nameAt = le32(entries, e + 16)
            val end = (nameAt until names.size).firstOrNull { names[it].toInt() == 0 } ?: names.size
            String(names, nameAt, end - nameAt, Charsets.US_ASCII) to (data + le64(entries, e) to le64(entries, e + 8))
        }
    }

    private fun le64(b: ByteArray, at: Int): Long =
        (0 until 8).fold(0L) { acc, i -> acc or ((b[at + i].toLong() and 0xFF) shl (8 * i)) }

    /** A GameCube/Wii game ID ("GM4E01") from an ISO, GCM, WBFS, CISO, RVZ or WIA file. */
    fun dolphin(file: File): String? = runCatching {
        RandomAccessFile(file, "r").use { f ->
            val magic = ByteArray(4).also { f.readFully(it) }
            val at = when (String(magic, Charsets.US_ASCII)) {
                "RVZ\u0001", "WIA\u0001" -> 0x58L
                "WBFS" -> 0x200L
                "CISO" -> 0x8000L
                else -> 0L
            }
            val id = ByteArray(6).also { f.seek(at); f.readFully(it) }
            String(id, Charsets.US_ASCII).takeIf { it.matches(Regex("[A-Z0-9]{6}")) }
        }
    }.getOrNull()

    private fun le32(b: ByteArray, at: Int) =
        (b[at].toInt() and 0xFF) or ((b[at + 1].toInt() and 0xFF) shl 8) or
            ((b[at + 2].toInt() and 0xFF) shl 16) or ((b[at + 3].toInt() and 0xFF) shl 24)

    /** Eden's cache/launched.json: title ID → when it was last started (epoch seconds). */
    fun edenLaunches(json: String?): Map<String, Long> = runCatching {
        val o = JSONObject(json ?: return emptyMap())
        o.keys().asSequence().associate { k -> k.uppercase() to (o.optJSONObject(k)?.optLong("timestamp") ?: 0L) }
    }.getOrDefault(emptyMap())

    /**
     * [file]'s title ID from its last session, for games played before
     * Pocket Automator watched: ES-DE writes a game's "last played" as you
     * come back from it ([ended], epoch seconds by ROM file), and Eden notes
     * each title's latest start ([launches]). The session's start is the
     * latest Eden start before it ended, within 8 hours, as long as no other
     * game from ES-DE ended in between and the title isn't another game's
     * already ([taken]).
     */
    fun fromSessions(file: String, ended: Map<String, Long>, launches: Map<String, Long>, taken: Set<String>): String? {
        val end = ended[file]?.takeIf { it > 0 } ?: return null
        val (id, start) = launches.filter { (_, t) -> t <= end + 60 }.maxByOrNull { it.value } ?: return null
        if (end - start > 8 * 3600) return null
        if (ended.any { (other, t) -> other != file && t in (start + 1) until end }) return null
        return base(id).takeIf { it !in taken }
    }

    /**
     * The title just started: the one launched at [since] or up to two
     * minutes after (epoch seconds), and the latest of them.
     */
    fun startedAfter(launches: Map<String, Long>, since: Long): String? =
        launches.filter { (_, t) -> t in (since - 5)..(since + 120) }.maxByOrNull { it.value }?.key?.let(::base)
}

/**
 * AES-128-XTS as the Switch uses it for NCA headers: 32-byte key (data key
 * then tweak key), and the sector number as a big-endian tweak.
 */
object Xts {

    fun decrypt(data: ByteArray, key: ByteArray, sectorSize: Int, firstSector: Long = 0): ByteArray {
        val dataKey = javax.crypto.spec.SecretKeySpec(key.copyOfRange(0, 16), "AES")
        val tweakKey = javax.crypto.spec.SecretKeySpec(key.copyOfRange(16, 32), "AES")
        val aesDecrypt = javax.crypto.Cipher.getInstance("AES/ECB/NoPadding").apply { init(javax.crypto.Cipher.DECRYPT_MODE, dataKey) }
        val aesTweak = javax.crypto.Cipher.getInstance("AES/ECB/NoPadding").apply { init(javax.crypto.Cipher.ENCRYPT_MODE, tweakKey) }
        val out = ByteArray(data.size)
        var sector = firstSector
        var at = 0
        while (at + 16 <= data.size) {
            val tweakIn = ByteArray(16)
            for (i in 0 until 8) tweakIn[15 - i] = (sector ushr (8 * i)).toByte()
            val tweak = aesTweak.doFinal(tweakIn)
            val end = minOf(at + sectorSize, data.size)
            while (at + 16 <= end) {
                val block = ByteArray(16) { (data[at + it].toInt() xor tweak[it].toInt()).toByte() }
                val plain = aesDecrypt.doFinal(block)
                for (i in 0 until 16) out[at + i] = (plain[i].toInt() xor tweak[i].toInt()).toByte()
                // Multiply the tweak by x in GF(2^128), little-endian.
                var carry = 0
                for (i in 0 until 16) {
                    val b = tweak[i].toInt() and 0xFF
                    tweak[i] = ((b shl 1) or carry).toByte()
                    carry = b ushr 7
                }
                if (carry != 0) tweak[0] = (tweak[0].toInt() xor 0x87).toByte()
                at += 16
            }
            sector++
        }
        return out
    }
}
