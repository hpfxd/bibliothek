#!/bin/bash

# ./handleBuild.sh <project> <version> <build> <commit>
# ./handleBuild.sh geyser 2.1.0 1271 0b80c589584129ba2280463f4c65fcd84c6d77db

PROJECT=$1
VERSION=$2
BUILD=$3
COMMIT=$4

echo "Project: $PROJECT"
echo "Version: $VERSION"
echo "Build: $BUILD"
echo "Commit: $COMMIT"
echo

# Load the .env file
export $(cat .env | xargs)

# Setup some variables
PROJECT_NAME=${PROJECT^}
VERSION_GROUP=${VERSION%.*}

# Clone the current commit of Geyser and checkout the commit we want to insert
git clone -n $REPO ./repo
git -C ./repo checkout $COMMIT

DOWNLOADS=""

# Get the files and build the download strings
for F in ./files/*.jar; do
	echo "Processing $F"

	echo "Getting SHA256"
	SHA256=$(sha256sum $F | cut -d ' ' -f 1)

	# Get id from file
	ID=$(echo $F | cut -d '-' -f 2 | cut -d '.' -f 1)

	# Convert ID to lowercase
	ID=$(echo $ID | tr '[:upper:]' '[:lower:]')

	echo "Generating download string"
	DOWNLOAD_STRING="$ID:/app/files/$(basename $F):$SHA256:$(basename $F)"

	echo "Adding download string to array"
	DOWNLOADS+="--download=$DOWNLOAD_STRING "
done

# Launch a docker container that can see the mongo database and run the insertBuild.js script
docker run --rm --network="$NETWORK" -e MONGODB_URL=$MONGODB_URL -v $(pwd):/app -v $(pwd)/repo:/repo -v $STORAGE_DIR:/storage node:lts node /app/insertBuild.js \
	--projectName=$PROJECT \
	--projectFriendlyName=$PROJECT_NAME \
	--versionGroupName=$VERSION_GROUP \
	--versionName=$VERSION \
	--buildNumber=$BUILD \
	--repositoryPath=/repo \
	--storagePath=/storage \
	$DOWNLOADS

rm -rf ./repo
rm -rf ./files/*
