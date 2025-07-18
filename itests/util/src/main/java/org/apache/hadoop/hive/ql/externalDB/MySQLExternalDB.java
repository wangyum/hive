/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.hadoop.hive.ql.externalDB;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Designed for MySQL external database connection
 */
public class MySQLExternalDB extends AbstractExternalDB {

    public MySQLExternalDB() {
    }

    @Override
    public String getRootUser() {
        return "root";
    }

    @Override
    public String getJdbcUrl() {
        return "jdbc:mysql://" + getContainerHostAddress() + ":" + getPort() + "/" + dbName;
    }

    public String getJdbcDriver() {
        return "com.mysql.jdbc.Driver";
    }

    @Override
    protected int getPort() {
        return 3306;
    }

    public String getDockerImageName() { return "mysql:8.4.3"; }

    public String[] getDockerAdditionalArgs() {
        return new String[] { "-p", getPort() + ":3306",
                          "-e", "MYSQL_ROOT_PASSWORD=" + getRootPassword(),
                          "-e", "MYSQL_DATABASE=" + dbName,
                          "-d"
        };
    }

    public boolean isContainerReady(ProcessResults pr) {
        Pattern pat = Pattern.compile("mysqld.*ready for connections.*port.*3306");
        Matcher m = pat.matcher(pr.stderr);
        return m.find();
    }
}