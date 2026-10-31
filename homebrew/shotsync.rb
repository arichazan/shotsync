# Homebrew formula TEMPLATE for a future tap (e.g. github.com/arichazan/homebrew-tap,
# file Formula/shotsync.rb). Not usable until a GitHub release exists.
#
# To publish a release:
#   1. ./scripts/release_mac.sh 0.1.0      (on macOS; signs/notarizes if certs are configured)
#   2. Create GitHub release v0.1.0 and upload dist/shotsync-0.1.0-darwin-universal.tar.gz
#   3. Replace VERSION and SHA256 below with the values from the .sha256 file
#   4. Commit to the tap repo; users run: brew install arichazan/tap/shotsync
class Shotsync < Formula
  desc "Receive Android screenshots and screen recordings on your Mac over pinned HTTPS"
  homepage "https://arichazan.github.io/apps/shotsync.html"
  version "VERSION"
  url "https://github.com/arichazan/shotsync/releases/download/v#{version}/shotsync-#{version}-darwin-universal.tar.gz"
  sha256 "SHA256"
  license "MIT"

  depends_on :macos

  def install
    bin.install "shotsync"
  end

  def caveats
    <<~EOS
      Install the receiver as a login (launchd) agent and choose a folder:
        shotsync install --destination "$HOME/Media/ShotSync"
      Show the pairing QR code:
        shotsync gen-qr        (or: shotsync menu  -> "Show Pairing QR Code...")
      Note: `shotsync install` copies the binary into ~/.local/bin/shotsync,
      so after `brew upgrade shotsync` re-run `shotsync install` to update the agent.
    EOS
  end

  test do
    assert_match "shotsync #{version}", shell_output("#{bin}/shotsync version")
  end
end
