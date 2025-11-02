#
# To learn more about a Podspec see http://guides.cocoapods.org/syntax/podspec.html.
# Run `pod lib lint ar_flutter_plugin_plus.podspec` to validate before publishing.
#
Pod::Spec.new do |s|
  s.name             = 'ar_flutter_plugin_plus'
  s.version          = '1.0.0'
  s.summary          = 'A Flutter plugin for AR experiences.'
  s.description      = <<-DESC
A Flutter plugin for AR experiences supporting Android and iOS.
                       DESC
  s.homepage         = 'https://xinix.tech/'
  s.license          = { :file => '../LICENSE' }
  s.author           = { 'Franz Graaf' => 'franz@xinix.tech' }
  s.source           = { :path => '.' }
  s.source_files = 'Classes/**/*'
  s.dependency 'Flutter'
  s.dependency 'GLTFSceneKit'
  s.dependency 'SwiftJWT'
  s.static_framework = true
  #s.dependency 'ARCore/CloudAnchors', '~> 1.12.0'
  #s.dependency 'ARCore', '~> 1.2.0'
  s.dependency 'ARCore/CloudAnchors', '~> 1.51.0' # Updated to support Firebase 12.x
  s.platform = :ios, '15.0'


  # Flutter.framework does not contain a i386 slice.
  s.pod_target_xcconfig = { 'DEFINES_MODULE' => 'YES', 'EXCLUDED_ARCHS[sdk=iphonesimulator*]' => 'i386' }
  s.swift_version = '5.0'
end
