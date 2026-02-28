require "json"

package = JSON.parse(File.read(File.join(__dir__, "package.json")))

Pod::Spec.new do |s|
  s.name         = "fast-issue-export"
  s.version      = package["version"]
  s.summary      = package["description"]
  s.homepage     = "https://github.com/user/fast-issue-export"
  s.license      = package["license"]
  s.authors      = package["author"]
  s.source       = { :git => "https://github.com/user/fast-issue-export.git", :tag => s.version }

  s.platforms    = { :ios => "15.0" }
  s.source_files = "ios/**/*.{h,m,mm,swift}"

  s.dependency "React-Core"

  s.swift_version = "5.0"
end
