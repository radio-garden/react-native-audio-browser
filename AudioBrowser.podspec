require "json"

package = JSON.parse(File.read(File.join(__dir__, "package.json")))

Pod::Spec.new do |s|
  s.name         = "AudioBrowser"
  s.version      = package["version"]
  s.summary      = package["description"]
  s.homepage     = package["homepage"]
  s.license      = package["license"]
  s.authors      = package["author"]

  s.platforms    = { :ios => min_ios_version_supported, :visionos => 1.0 }
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

  # Public headers for CarPlay scene delegate
  s.public_header_files = ["ios/CarPlay/*.h"]

  load 'nitrogen/generated/ios/AudioBrowser+autolinking.rb'
  add_nitrogen_files(s)

  s.dependency 'React-jsi'
  s.dependency 'React-callinvoker'
  s.dependency 'SDWebImage'
  install_modules_dependencies(s)

  # CarPlay framework for CarPlay support
  s.frameworks = 'CarPlay'
end
