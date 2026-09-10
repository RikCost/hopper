#!/usr/bin/env ruby
# frozen_string_literal: true
#
# WARNING: Do not run this script unless you intend to reset the Xcode project.
# It overwrites Hopper.xcodeproj (platform, signing, embed settings). Prefer
# adding new source files manually in Xcode.

require "fileutils"
require "securerandom"

ROOT = File.expand_path("..", __dir__)
PROJECT_DIR = File.join(ROOT, "Hopper.xcodeproj")
APP_DIR = ROOT

def uuid
  SecureRandom.hex(12).upcase
end

shared_swift = Dir[File.join(APP_DIR, "Shared", "*.swift")].sort
tunnel_swift = Dir[File.join(APP_DIR, "TunnelCore", "*.swift")].sort
app_swift = Dir[File.join(APP_DIR, "Hopper", "*.swift")].sort
ext_swift = Dir[File.join(APP_DIR, "HopperExtension", "*.swift")].sort

files = {}
refs = {}
groups = {}

def add_file(path, group, files, refs, groups)
  id = uuid
  refs[path] = id
  files[id] = { path: path, group: group }
  groups[group] ||= []
  groups[group] << id
  id
end

shared_swift.each { |f| add_file(f, "Shared", files, refs, groups) }
tunnel_swift.each { |f| add_file(f, "TunnelCore", files, refs, groups) }
app_swift.each { |f| add_file(f, "Hopper", files, refs, groups) }
ext_swift.each { |f| add_file(f, "HopperExtension", files, refs, groups) }

hopper_target = uuid
extension_target = uuid
project_uuid = uuid
products_group = uuid
main_group = uuid
frameworks_group = uuid
hopper_product = uuid
extension_product = uuid
shared_group = uuid
tunnel_group = uuid
hopper_group = uuid
extension_group = uuid
config_list_project = uuid
config_list_hopper = uuid
config_list_extension = uuid
debug_project = uuid
release_project = uuid
debug_hopper = uuid
release_hopper = uuid
debug_extension = uuid
release_extension = uuid
citadel_package = uuid
citadel_product = uuid
package_ref = uuid
hopper_sources = uuid
extension_sources = uuid
hopper_frameworks = uuid
extension_frameworks = uuid
hopper_resources = uuid
hopper_assets = uuid
hopper_plist = uuid
extension_plist = uuid
hopper_entitlements = uuid
extension_entitlements = uuid
hopper_embed = uuid
extension_embed = uuid

def build_file(file_id, build_phase)
  "#{uuid} /* #{File.basename(files[file_id][:path])} in Sources */ = {isa = PBXBuildFile; fileRef = #{file_id}; };"
end

hopper_build_files = []
extension_build_files = []

(shared_swift + app_swift).each do |path|
  id = refs[path]
  hopper_build_files << [uuid, id]
end

(shared_swift + tunnel_swift + ext_swift).each do |path|
  id = refs[path]
  extension_build_files << [uuid, id]
end

pbx = <<~PBXPROJ
  // !$*UTF8*$!
  {
  	archiveVersion = 1;
  	classes = {};
  	objectVersion = 60;
  	objects = {

  /* Begin PBXBuildFile section */
  #{hopper_build_files.map { |bf, fr| "#{bf} /* #{File.basename(files[fr][:path])} in Sources */ = {isa = PBXBuildFile; fileRef = #{fr}; };" }.join("\n  ")}
  #{extension_build_files.map { |bf, fr| "#{bf} /* #{File.basename(files[fr][:path])} in Sources */ = {isa = PBXBuildFile; fileRef = #{fr}; };" }.join("\n  ")}
  #{uuid} /* Assets.xcassets in Resources */ = {isa = PBXBuildFile; fileRef = #{hopper_assets}; };
  #{uuid} /* HopperExtension.appex in Embed Foundation Extensions */ = {isa = PBXBuildFile; fileRef = #{extension_product}; settings = {ATTRIBUTES = (RemoveHeadersOnCopy, ); }; };
  #{uuid} /* Citadel in Frameworks */ = {isa = PBXBuildFile; productRef = #{citadel_product}; };
  #{uuid} /* Citadel in Frameworks */ = {isa = PBXBuildFile; productRef = #{citadel_product}; };
  /* End PBXBuildFile section */

  /* Begin PBXContainerItemProxy section */
  #{uuid} /* PBXContainerItemProxy */ = {
  	isa = PBXContainerItemProxy;
  	containerPortal = #{project_uuid} /* Project object */;
  	proxyType = 1;
  	remoteGlobalIDString = #{extension_target};
  	remoteInfo = HopperExtension;
  };
  /* End PBXContainerItemProxy section */

  /* Begin PBXCopyFilesBuildPhase section */
  #{hopper_embed} /* Embed Foundation Extensions */ = {
  	isa = PBXCopyFilesBuildPhase;
  	buildActionMask = 2147483647;
  	dstPath = "";
  	dstSubfolderSpec = 13;
  	files = (
  	);
  	name = "Embed Foundation Extensions";
  	runOnlyForDeploymentPostprocessing = 0;
  };
  /* End PBXCopyFilesBuildPhase section */

  /* Begin PBXFileReference section */
  #{hopper_product} /* Hopper.app */ = {isa = PBXFileReference; explicitFileType = wrapper.application; includeInIndex = 0; path = Hopper.app; sourceTree = BUILT_PRODUCTS_DIR; };
  #{extension_product} /* HopperExtension.appex */ = {isa = PBXFileReference; explicitFileType = "wrapper.app-extension"; includeInIndex = 0; path = HopperExtension.appex; sourceTree = BUILT_PRODUCTS_DIR; };
  #{hopper_assets} /* Assets.xcassets */ = {isa = PBXFileReference; lastKnownFileType = folder.assetcatalog; path = Assets.xcassets; sourceTree = "<group>"; };
  #{hopper_plist} /* Info.plist */ = {isa = PBXFileReference; lastKnownFileType = text.plist.xml; path = Info.plist; sourceTree = "<group>"; };
  #{extension_plist} /* Info.plist */ = {isa = PBXFileReference; lastKnownFileType = text.plist.xml; path = Info.plist; sourceTree = "<group>"; };
  #{hopper_entitlements} /* Hopper.entitlements */ = {isa = PBXFileReference; lastKnownFileType = text.plist.entitlements; path = Hopper.entitlements; sourceTree = "<group>"; };
  #{extension_entitlements} /* HopperExtension.entitlements */ = {isa = PBXFileReference; lastKnownFileType = text.plist.entitlements; path = HopperExtension.entitlements; sourceTree = "<group>"; };
  #{files.map { |id, meta| "#{id} /* #{File.basename(meta[:path])} */ = {isa = PBXFileReference; lastKnownFileType = sourcecode.swift; path = #{File.basename(meta[:path])}; sourceTree = \"<group>\"; };" }.join("\n  ")}
  /* End PBXFileReference section */

  /* Begin PBXFrameworksBuildPhase section */
  #{hopper_frameworks} /* Frameworks */ = {
  	isa = PBXFrameworksBuildPhase;
  	buildActionMask = 2147483647;
  	files = (
  	);
  	runOnlyForDeploymentPostprocessing = 0;
  };
  #{extension_frameworks} /* Frameworks */ = {
  	isa = PBXFrameworksBuildPhase;
  	buildActionMask = 2147483647;
  	files = (
  	);
  	runOnlyForDeploymentPostprocessing = 0;
  };
  /* End PBXFrameworksBuildPhase section */

  /* Begin PBXGroup section */
  #{main_group} = {
  	isa = PBXGroup;
  	children = (
  		#{hopper_group} /* Hopper */,
  		#{extension_group} /* HopperExtension */,
  		#{shared_group} /* Shared */,
  		#{tunnel_group} /* TunnelCore */,
  		#{products_group} /* Products */,
  		#{frameworks_group} /* Frameworks */,
  	);
  	sourceTree = "<group>";
  };
  #{products_group} /* Products */ = {
  	isa = PBXGroup;
  	children = (
  		#{hopper_product} /* Hopper.app */,
  		#{extension_product} /* HopperExtension.appex */,
  	);
  	name = Products;
  	sourceTree = "<group>";
  };
  #{shared_group} /* Shared */ = {
  	isa = PBXGroup;
  	children = (
  		#{groups["Shared"].join(",\n\t\t")}
  	);
  	path = Shared;
  	sourceTree = "<group>";
  };
  #{tunnel_group} /* TunnelCore */ = {
  	isa = PBXGroup;
  	children = (
  		#{groups["TunnelCore"].join(",\n\t\t")}
  	);
  	path = TunnelCore;
  	sourceTree = "<group>";
  };
  #{hopper_group} /* Hopper */ = {
  	isa = PBXGroup;
  	children = (
  		#{groups["Hopper"].join(",\n\t\t")},
  		#{hopper_assets} /* Assets.xcassets */,
  		#{hopper_plist} /* Info.plist */,
  		#{hopper_entitlements} /* Hopper.entitlements */,
  	);
  	path = Hopper;
  	sourceTree = "<group>";
  };
  #{extension_group} /* HopperExtension */ = {
  	isa = PBXGroup;
  	children = (
  		#{groups["HopperExtension"].join(",\n\t\t")},
  		#{extension_plist} /* Info.plist */,
  		#{extension_entitlements} /* HopperExtension.entitlements */,
  	);
  	path = HopperExtension;
  	sourceTree = "<group>";
  };
  #{frameworks_group} /* Frameworks */ = {
  	isa = PBXGroup;
  	children = (
  	);
  	name = Frameworks;
  	sourceTree = "<group>";
  };
  /* End PBXGroup section */

  /* Begin PBXNativeTarget section */
  #{hopper_target} /* Hopper */ = {
  	isa = PBXNativeTarget;
  	buildConfigurationList = #{config_list_hopper} /* Build configuration list for PBXNativeTarget "Hopper" */;
  	buildPhases = (
  		#{hopper_sources} /* Sources */,
  		#{hopper_frameworks} /* Frameworks */,
  		#{hopper_resources} /* Resources */,
  		#{hopper_embed} /* Embed Foundation Extensions */,
  	);
  	buildRules = (
  	);
  	dependencies = (
  	);
  	name = Hopper;
  	packageProductDependencies = (
  	);
  	productName = Hopper;
  	productReference = #{hopper_product} /* Hopper.app */;
  	productType = "com.apple.product-type.application";
  };
  #{extension_target} /* HopperExtension */ = {
  	isa = PBXNativeTarget;
  	buildConfigurationList = #{config_list_extension} /* Build configuration list for PBXNativeTarget "HopperExtension" */;
  	buildPhases = (
  		#{extension_sources} /* Sources */,
  		#{extension_frameworks} /* Frameworks */,
  	);
  	buildRules = (
  	);
  	dependencies = (
  	);
  	name = HopperExtension;
  	packageProductDependencies = (
  		#{citadel_product} /* Citadel */,
  	);
  	productName = HopperExtension;
  	productReference = #{extension_product} /* HopperExtension.appex */;
  	productType = "com.apple.product-type.app-extension";
  };
  /* End PBXNativeTarget section */

  /* Begin PBXProject section */
  #{project_uuid} /* Project object */ = {
  	isa = PBXProject;
  	attributes = {
  		BuildIndependentTargetsInParallel = 1;
  		LastUpgradeCheck = 1600;
  	};
  	buildConfigurationList = #{config_list_project} /* Build configuration list for PBXProject "Hopper" */;
  	compatibilityVersion = "Xcode 15.0";
  	developmentRegion = en;
  	hasScannedForEncodings = 0;
  	knownRegions = (
  		en,
  		Base,
  	);
  	mainGroup = #{main_group};
  	packageReferences = (
  		#{package_ref} /* XCRemoteSwiftPackageReference "Citadel" */,
  	);
  	productRefGroup = #{products_group} /* Products */;
  	projectDirPath = "";
  	projectRoot = "";
  	targets = (
  		#{hopper_target} /* Hopper */,
  		#{extension_target} /* HopperExtension */,
  	);
  };
  /* End PBXProject section */

  /* Begin PBXResourcesBuildPhase section */
  #{hopper_resources} /* Resources */ = {
  	isa = PBXResourcesBuildPhase;
  	buildActionMask = 2147483647;
  	files = (
  	);
  	runOnlyForDeploymentPostprocessing = 0;
  };
  /* End PBXResourcesBuildPhase section */

  /* Begin PBXSourcesBuildPhase section */
  #{hopper_sources} /* Sources */ = {
  	isa = PBXSourcesBuildPhase;
  	buildActionMask = 2147483647;
  	files = (
  		#{hopper_build_files.map { |bf, _| bf }.join(",\n\t\t")}
  	);
  	runOnlyForDeploymentPostprocessing = 0;
  };
  #{extension_sources} /* Sources */ = {
  	isa = PBXSourcesBuildPhase;
  	buildActionMask = 2147483647;
  	files = (
  		#{extension_build_files.map { |bf, _| bf }.join(",\n\t\t")}
  	);
  	runOnlyForDeploymentPostprocessing = 0;
  };
  /* End PBXSourcesBuildPhase section */

  /* Begin XCBuildConfiguration section */
  #{debug_project} /* Debug */ = {
  	isa = XCBuildConfiguration;
  	buildSettings = {
  		CLANG_ENABLE_MODULES = YES;
  		IPHONEOS_DEPLOYMENT_TARGET = 17.0;
  		SWIFT_VERSION = 5.0;
  	};
  	name = Debug;
  };
  #{release_project} /* Release */ = {
  	isa = XCBuildConfiguration;
  	buildSettings = {
  		CLANG_ENABLE_MODULES = YES;
  		IPHONEOS_DEPLOYMENT_TARGET = 17.0;
  		SWIFT_VERSION = 5.0;
  	};
  	name = Release;
  };
  #{debug_hopper} /* Debug */ = {
  	isa = XCBuildConfiguration;
  	buildSettings = {
  		ASSETCATALOG_COMPILER_APPICON_NAME = AppIcon;
  		CODE_SIGN_ENTITLEMENTS = Hopper/Hopper.entitlements;
  		CODE_SIGN_STYLE = Automatic;
  		CURRENT_PROJECT_VERSION = 1;
  		DEVELOPMENT_TEAM = "";
  		GENERATE_INFOPLIST_FILE = YES;
  		INFOPLIST_FILE = Hopper/Info.plist;
  		INFOPLIST_KEY_CFBundleDisplayName = "ɹǝddoH";
  		INFOPLIST_KEY_UIApplicationSupportsIndirectInputEvents = YES;
  		INFOPLIST_KEY_UISupportedInterfaceOrientations = UIInterfaceOrientationPortrait;
  		INFOPLIST_KEY_UISupportedInterfaceOrientations_iPad = "UIInterfaceOrientationPortrait UIInterfaceOrientationPortraitUpsideDown UIInterfaceOrientationLandscapeLeft UIInterfaceOrientationLandscapeRight";
  		LD_RUNPATH_SEARCH_PATHS = (
  			"$(inherited)",
  			"@executable_path/Frameworks",
  		);
  		MARKETING_VERSION = 3.0.0;
  		PRODUCT_BUNDLE_IDENTIFIER = com.aengix.hopper;
  		PRODUCT_NAME = Hopper;
  		SWIFT_EMIT_LOC_STRINGS = YES;
  		TARGETED_DEVICE_FAMILY = "1,2";
  	};
  	name = Debug;
  };
  #{release_hopper} /* Release */ = {
  	isa = XCBuildConfiguration;
  	buildSettings = {
  		ASSETCATALOG_COMPILER_APPICON_NAME = AppIcon;
  		CODE_SIGN_ENTITLEMENTS = Hopper/Hopper.entitlements;
  		CODE_SIGN_STYLE = Automatic;
  		CURRENT_PROJECT_VERSION = 1;
  		DEVELOPMENT_TEAM = "";
  		GENERATE_INFOPLIST_FILE = YES;
  		INFOPLIST_FILE = Hopper/Info.plist;
  		INFOPLIST_KEY_CFBundleDisplayName = "ɹǝddoH";
  		INFOPLIST_KEY_UIApplicationSupportsIndirectInputEvents = YES;
  		INFOPLIST_KEY_UISupportedInterfaceOrientations = UIInterfaceOrientationPortrait;
  		INFOPLIST_KEY_UISupportedInterfaceOrientations_iPad = "UIInterfaceOrientationPortrait UIInterfaceOrientationPortraitUpsideDown UIInterfaceOrientationLandscapeLeft UIInterfaceOrientationLandscapeRight";
  		LD_RUNPATH_SEARCH_PATHS = (
  			"$(inherited)",
  			"@executable_path/Frameworks",
  		);
  		MARKETING_VERSION = 3.0.0;
  		PRODUCT_BUNDLE_IDENTIFIER = com.aengix.hopper;
  		PRODUCT_NAME = Hopper;
  		SWIFT_EMIT_LOC_STRINGS = YES;
  		TARGETED_DEVICE_FAMILY = "1,2";
  	};
  	name = Release;
  };
  #{debug_extension} /* Debug */ = {
  	isa = XCBuildConfiguration;
  	buildSettings = {
  		CODE_SIGN_ENTITLEMENTS = HopperExtension/HopperExtension.entitlements;
  		CODE_SIGN_STYLE = Automatic;
  		CURRENT_PROJECT_VERSION = 1;
  		DEVELOPMENT_TEAM = "";
  		GENERATE_INFOPLIST_FILE = YES;
  		INFOPLIST_FILE = HopperExtension/Info.plist;
  		LD_RUNPATH_SEARCH_PATHS = (
  			"$(inherited)",
  			"@executable_path/Frameworks",
  			"@executable_path/../../Frameworks",
  		);
  		MARKETING_VERSION = 3.0.0;
  		PRODUCT_BUNDLE_IDENTIFIER = com.aengix.hopper.tunnel;
  		PRODUCT_NAME = HopperExtension;
  		SKIP_INSTALL = YES;
  		SWIFT_VERSION = 5.0;
  		TARGETED_DEVICE_FAMILY = "1,2";
  	};
  	name = Debug;
  };
  #{release_extension} /* Release */ = {
  	isa = XCBuildConfiguration;
  	buildSettings = {
  		CODE_SIGN_ENTITLEMENTS = HopperExtension/HopperExtension.entitlements;
  		CODE_SIGN_STYLE = Automatic;
  		CURRENT_PROJECT_VERSION = 1;
  		DEVELOPMENT_TEAM = "";
  		GENERATE_INFOPLIST_FILE = YES;
  		INFOPLIST_FILE = HopperExtension/Info.plist;
  		LD_RUNPATH_SEARCH_PATHS = (
  			"$(inherited)",
  			"@executable_path/Frameworks",
  			"@executable_path/../../Frameworks",
  		);
  		MARKETING_VERSION = 3.0.0;
  		PRODUCT_BUNDLE_IDENTIFIER = com.aengix.hopper.tunnel;
  		PRODUCT_NAME = HopperExtension;
  		SKIP_INSTALL = YES;
  		SWIFT_VERSION = 5.0;
  		TARGETED_DEVICE_FAMILY = "1,2";
  	};
  	name = Release;
  };
  /* End XCBuildConfiguration section */

  /* Begin XCConfigurationList section */
  #{config_list_project} /* Build configuration list for PBXProject "Hopper" */ = {
  	isa = XCConfigurationList;
  	buildConfigurations = (
  		#{debug_project} /* Debug */,
  		#{release_project} /* Release */,
  	);
  	defaultConfigurationIsVisible = 0;
  	defaultConfigurationName = Release;
  };
  #{config_list_hopper} /* Build configuration list for PBXNativeTarget "Hopper" */ = {
  	isa = XCConfigurationList;
  	buildConfigurations = (
  		#{debug_hopper} /* Debug */,
  		#{release_hopper} /* Release */,
  	);
  	defaultConfigurationIsVisible = 0;
  	defaultConfigurationName = Release;
  };
  #{config_list_extension} /* Build configuration list for PBXNativeTarget "HopperExtension" */ = {
  	isa = XCConfigurationList;
  	buildConfigurations = (
  		#{debug_extension} /* Debug */,
  		#{release_extension} /* Release */,
  	);
  	defaultConfigurationIsVisible = 0;
  	defaultConfigurationName = Release;
  };
  /* End XCConfigurationList section */

  /* Begin XCRemoteSwiftPackageReference section */
  #{package_ref} /* XCRemoteSwiftPackageReference "Citadel" */ = {
  	isa = XCRemoteSwiftPackageReference;
  	repositoryURL = "https://github.com/orlandos-nl/Citadel";
  	requirement = {
  		kind = upToNextMajorVersion;
  		minimumVersion = 0.12.0;
  	};
  };
  /* End XCRemoteSwiftPackageReference section */

  /* Begin XCSwiftPackageProductDependency section */
  #{citadel_product} /* Citadel */ = {
  	isa = XCSwiftPackageProductDependency;
  	package = #{package_ref} /* XCRemoteSwiftPackageReference "Citadel" */;
  	productName = Citadel;
  };
  /* End XCSwiftPackageProductDependency section */

  	};
  	rootObject = #{project_uuid} /* Project object */;
  }
PBXPROJ

FileUtils.rm_rf(PROJECT_DIR)
FileUtils.mkdir_p(PROJECT_DIR)
File.write(File.join(PROJECT_DIR, "project.pbxproj"), pbx)
puts "Generated #{PROJECT_DIR}"
