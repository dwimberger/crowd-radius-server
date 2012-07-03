#!/bin/sh
MAIN_CLASS=net.wimpi.crowd.radius.Server
mvn exec:java -Dexec.mainClass="$MAIN_CLASS" -Dexec.args="$*"
