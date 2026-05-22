# AUR Setup Guide

## Prerequisites

1. Create an AUR account at https://aur.archlinux.org/
2. Add your SSH public key (`C:\Users\华硕\AUR.pub`) to your AUR account settings

## Setup Steps

### 1. Register AUR Package

First, register the package name on AUR:

```bash
ssh aur@aur.archlinux.org setup-repo xenon-mdt
```

### 2. Configure GitHub Secrets

Add the following secret to your GitHub repository (Settings → Secrets and variables → Actions):

- **Name**: `AUR_SSH_PRIVATE_KEY`
- **Value**: Contents of your private key file (`C:\Users\华硕\AUR`)

### 3. Initial AUR Package Setup

Clone the AUR repository and add the initial files:

```bash
git clone ssh://aur@aur.archlinux.org/xenon-mdt.git
cd xenon-mdt
cp /path/to/Xenon-worktree3/aur/PKGBUILD .
cp /path/to/Xenon-worktree3/aur/.SRCINFO .
git add PKGBUILD .SRCINFO
git commit -m "Initial package setup"
git push
```

## How It Works

1. When you push a tag (e.g., `v0.2.0`), the GitHub Action will:
   - Build the JAR and portable ZIP
   - Create a GitHub Release with the artifacts
   - Automatically update the AUR package with the new version

2. The AUR package will always be synced with the latest release

## Testing Locally

To test the PKGBUILD locally on Arch Linux:

```bash
makepkg -si
```

## Manual AUR Update

If you need to manually update the AUR package:

```bash
git clone ssh://aur@aur.archlinux.org/xenon-mdt.git
cd xenon-mdt
# Edit PKGBUILD to update pkgver
sed -i "s/pkgver=.*/pkgver=0.2.0/" PKGBUILD
sed -i "s/pkgrel=.*/pkgrel=1/" PKGBUILD
updpkgsums
makepkg --printsrcinfo > .SRCINFO
git add PKGBUILD .SRCINFO
git commit -m "Update to v0.2.0"
git push
```
