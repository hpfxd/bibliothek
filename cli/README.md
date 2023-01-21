# bibliothek cli
A simple cli for uploading files to bibliothek

## Setup
1. Download the cli folder to somewhere on your server
2. Copy `.env.example` to `.env` and customise
3. Run these bash commands to setup the user and everything else
```bash
# Create the user and give them docker perms
useradd -M -d $(pwd) build-upload
usermod -aG docker build-upload

# Create the .ssh dir for the user
mkdir .ssh
chown build-upload:build-upload .ssh

# Setup ssh for the user
su build-upload
ssh-keygen -t ecdsa -b 521
cp .ssh/id_ecdsa.pub .ssh/authorized_keys
exit

# Setup the files and repo dirs
mkdir files
mkdir repo
chown root:build-upload files repo
chmod 775 files repo

# Make the handleBuild.sh file executable
chmod +x handleBuild.sh

# Install depends
docker run --rm -v $(pwd):/app node:lts yarn --cwd /app install
```
4. Configure your build server to push files into the `files` folder and then run `./handleBuild.sh <project> <version> <build> <commit>` (EG: https://github.com/GeyserMC/Geyser/pull/3513)
