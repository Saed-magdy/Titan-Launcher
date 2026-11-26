# ============================================================================
#  Private build settings: EXAMPLE
#
#  1. Copy this file and name the copy  build.local.ps1  (in the same folder).
#  2. Put YOUR OWN folders in the copy. Remove the # at the start of a line to use it.
#  3. Run build_apk.ps1 as usual. It reads build.local.ps1 by itself.
#
#  build.local.ps1 is ignored by git: it stays on your PC and is never uploaded.
#  Never put the key FILES themselves in the project folder, only their location.
# ============================================================================

# Where the car's platform signing key and certificate are kept on this PC:
# $LocalPlatformKey  = "D:\keys\platform.pk8"
# $LocalPlatformCert = "D:\keys\platform.x509.pem"

# Where the Android SDK is, if ANDROID_SDK_ROOT is not set:
# $LocalSdkDir = "D:\android-sdk"
