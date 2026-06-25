require "json"

package = JSON.parse(File.read(File.join(__dir__, "package.json")))

Pod::Spec.new do |s|
  s.name         = "AudioBrowser"
  s.version      = package["version"]
  s.summary      = package["description"]
  s.homepage     = package["homepage"]
  s.license      = package["license"]
  s.authors      = package["author"]

  s.platforms    = { :ios => "16.0", :visionos => 1.0 }
  s.source       = { :git => "https://github.com/puckey/react-native-audio-browser.git", :tag => "#{s.version}" }

  s.swift_version = '6.2'

  s.source_files = [
    # Implementation (Swift)
    "ios/**/*.{swift}",
    # Autolinking/Registration (Objective-C++)
    "ios/**/*.{m,mm}",
    # Headers (Objective-C)
    "ios/**/*.{h}",
    # Implementation (C++ objects)
    "cpp/**/*.{hpp,cpp}",
  ]

  s.exclude_files = ["ios/Tests/**/*"]

  # Public headers for CarPlay scene delegate
  s.public_header_files = ["ios/CarPlay/*.h"]

  load 'nitrogen/generated/ios/AudioBrowser+autolinking.rb'
  add_nitrogen_files(s)

  s.dependency 'React-jsi'
  s.dependency 'React-callinvoker'
  s.dependency 'Kingfisher', '~> 8.6'
  s.dependency 'SwiftDraw', '~> 0.18'
  install_modules_dependencies(s)

  # CarPlay framework for CarPlay support
  s.frameworks = 'CarPlay', 'Intents'

  # --- Google Cast (compiled in by default; opt out for size) -------------
  #
  # Cast is ON by default and inert at runtime until configureCast() is called.
  # A plain `pod install` adds the `google-cast-sdk` pod and defines the
  # `AUDIOBROWSER_ENABLE_CAST` Swift active-compilation condition so the Cast
  # code under `ios/Cast/` compiles. An app that never casts behaves identically
  # (the runtime gate keeps it inert); it just links the SDK.
  #
  # Size-sensitive apps that never cast can OPT OUT by setting the env var
  # before `pod install` (the rare path):
  #
  #     AUDIOBROWSER_DISABLE_CAST=1 pod install
  #
  # which links no Cast SDK and compiles every file under `ios/Cast/` to its
  # inert `#else` no-op (byte-for-byte unchanged).
  #
  # To actually CAST, the consuming app must add Cast plumbing the library
  # cannot inject into its Info.plist (see docs and
  # ios/Cast/CastSessionManager.swift header):
  #   - NSLocalNetworkUsageDescription
  #   - NSBonjourServices: _googlecast._tcp and
  #     _<receiverAppId>._googlecast._tcp
  #   (iOS 14+ shows the local-network permission prompt on first discovery.)
  unless ENV['AUDIOBROWSER_DISABLE_CAST'] == '1'
    # `google-cast-sdk` ships the dynamic GoogleCast.framework. If the build
    # needs the no-Bluetooth variant, swap to 'google-cast-sdk-no-bluetooth'.
    s.dependency 'google-cast-sdk', '~> 4.8'
    s.pod_target_xcconfig = {
      'OTHER_SWIFT_FLAGS' => '$(inherited) -D AUDIOBROWSER_ENABLE_CAST',
      'SWIFT_ACTIVE_COMPILATION_CONDITIONS' => '$(inherited) AUDIOBROWSER_ENABLE_CAST',
    }
  end
end
