name: Build ElementalSwords

on:
  push:
  workflow_dispatch:

jobs:
  build:
    runs-on: ubuntu-latest
    steps:
      - uses: actions/checkout@v4
      - uses: actions/setup-java@v4
        with:
          distribution: temurin
          java-version: 21
          cache: maven
      - name: Build
        run: mvn -B package
      - name: Upload jar
        uses: actions/upload-artifact@v4
        with:
          name: ElementalSwords-jar
          path: target/ElementalSwords-1.0.0.jar
