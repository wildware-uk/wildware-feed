# Code signing policy

Free code signing provided by [SignPath.io](https://about.signpath.io), certificate by
[SignPath Foundation](https://signpath.org).

**Status:** Wildware Feed has applied to the SignPath Foundation's program for open-source
projects. Until it is accepted, Windows releases are unsigned and their release notes say so.

Certificates from this program are issued in SignPath Foundation's name, so Windows shows
**SignPath Foundation** as the publisher of a signed Wildware Feed.

## What is signed

Only files built from this repository are signed.

| Release file | What is signed |
| --- | --- |
| `Wildware-Feed-<version>-windows-x64.msi` | The installer, and the `Wildware Feed.exe` it installs |
| `Wildware-Feed-<version>-windows-x64-portable.zip` | `Wildware Feed.exe` inside it |

The Java runtime that ships beside the app comes from other open-source projects and is left as it
is. The Mac, Linux and Android files are not signed through SignPath (the Mac and Android ones are
signed with this project's own Apple and Android keys).

## How signed files are made

1. They are built only from this public repository's source, by the
   [`desktop-release`](../.github/workflows/desktop-release.yml) workflow on GitHub-hosted runners,
   when a version tag `v<version>` is pushed. Nothing built anywhere else (a laptop, a fork, another
   build service) is ever sent for signing.
2. The workflow uploads the unsigned `.msi` and `Wildware Feed.exe` as a GitHub Actions artifact
   and submits it to SignPath with SignPath's GitHub action. SignPath's GitHub connector checks
   that the artifact came from this repository's workflow before it signs anything.
3. The artifact configuration, [`.signpath/artifact-configuration.xml`](../.signpath/artifact-configuration.xml)
   (the same as the one saved in SignPath), names the exact files to sign. It requires every
   signed `Wildware Feed.exe` to have the product name `Wildware Feed` and the release's version.
   The build checks the same before it sends them: product name `Wildware Feed`, company
   `Wildware`, the release's version, and a description.
4. An approver approves each release's signing request by hand in SignPath. Nothing is signed
   without that.
5. The signed files are then published on the
   [Releases](https://github.com/wildware-uk/wildware-feed/releases) page, with checksums.

## Team roles

| Role | Members |
| --- | --- |
| Committers and reviewers | [Shaun Wild](https://github.com/shaun-wild) |
| Approvers | [Shaun Wild](https://github.com/shaun-wild) |

- **Committers** may change the source code and build scripts.
- **Reviewers** review every change from anyone who is not a committer. Such changes come in as
  pull requests and are merged only after a committer has reviewed them.
- **Approvers** approve each signing request in SignPath.

Everyone in these roles uses multi-factor authentication for GitHub and for SignPath.

## Privacy

Wildware Feed sends what you read and write only to the server you sign in to, and asks GitHub
for the latest release to offer updates. Turn the update check off with **Check for updates
automatically** in the tray menu. There are no analytics, no tracking and no crash reporting. The full
[privacy policy](../PRIVACY.md) says more.

## Installing and removing

[docs/INSTALL.md](INSTALL.md) says how to install Wildware Feed and how to remove it.

## Reporting a problem

If you find a signed file that you think was not built from this repository, or that does
something this page doesn't allow, open an issue at
[github.com/wildware-uk/wildware-feed/issues](https://github.com/wildware-uk/wildware-feed/issues).
