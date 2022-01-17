#!/usr/bin/env bash

#
# Licensed to the Apache Software Foundation (ASF) under one or more
# contributor license agreements.  See the NOTICE file distributed with
# this work for additional information regarding copyright ownership.
# The ASF licenses this file to You under the Apache License, Version 2.0
# (the "License"); you may not use this file except in compliance with
# the License.  You may obtain a copy of the License at
#
#    http://www.apache.org/licenses/LICENSE-2.0
#
# Unless required by applicable law or agreed to in writing, software
# distributed under the License is distributed on an "AS IS" BASIS,
# WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
# See the License for the specific language governing permissions and
# limitations under the License.
#

echo "#################"
echo $1
echo $2
echo "#################"

# Gather some user information.
if [ -z "$ASF_USERNAME" ]; then
  export ASF_USERNAME=$(read_config "ASF user" "$LOGNAME")
fi

if [ -z "$ASF_PASSWORD" ]; then
  stty -echo && printf "ASF password: " && read ASF_PASSWORD && printf '\n' && stty echo
fi

export ASF_PASSWORD


# Publish Hive to Maven staging repo
tmp_settings="tmp-settings.xml"
echo "<settings><servers><server>" > $tmp_settings
echo "<id>apache.snapshots.https</id><username>$ASF_USERNAME</username>" >> $tmp_settings
echo "<password>$ASF_PASSWORD</password>" >> $tmp_settings
echo "</server></servers></settings>" >> $tmp_settings

mvn --settings $tmp_settings -DskipTests clean deploy

rm $tmp_settings
cd ..
exit 0
