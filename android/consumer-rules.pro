# The Cast OptionsProvider is instantiated reflectively by play-services-cast-framework
# (referenced from the cast-sourceset AndroidManifest's OPTIONS_PROVIDER_CLASS_NAME meta-data), so
# R8/ProGuard must not strip or rename it. Harmless when Cast is disabled (the class is absent).
-keep class com.audiobrowser.cast.AudioBrowserCastOptionsProvider { *; }
