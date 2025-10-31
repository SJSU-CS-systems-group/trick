Pod::Spec.new do |s|
  s.name         = 'SignalBridge'
  s.version      = '0.1.0'
  s.summary      = 'C-ABI bridge to LibSignalClient Swift for Kotlin/Native.'
  s.description  = 'Exposes @_cdecl C functions that call Signal Foundation LibSignalClient Swift APIs.'
  s.homepage     = 'https://example.invalid/SignalBridge'
  s.license      = { :type => 'MIT', :text => 'MIT' }
  s.author       = { 'Bridge' => 'bridge@example.invalid' }
  s.platform     = :ios, '15.0'
  s.source       = { :path => '.' }
  s.swift_version = '5.0'

  s.source_files = 'Sources/SignalBridge/**/*.{swift,h}'
  s.public_header_files = 'Sources/SignalBridge/include/**/*.h'

  s.dependency 'LibSignalClient'
end


