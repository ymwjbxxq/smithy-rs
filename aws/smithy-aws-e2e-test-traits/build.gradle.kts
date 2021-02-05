/*
 * Copyright 2019 Amazon.com, Inc. or its affiliates. All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License").
 * You may not use this file except in compliance with the License.
 * A copy of the License is located at
 *
 *  http://aws.amazon.com/apache2.0
 *
 * or in the "license" file accompanying this file. This file is distributed
 * on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either
 * express or implied. See the License for the specific language governing
 * permissions and limitations under the License.
 */

plugins {
    kotlin("jvm")
}

tasks.test {
    useJUnitPlatform()
}

description = "Defines AWS Specific Smithy End to End Test Traits"

extra["displayName"] = "Smithy :: Protocol Test Traits"
extra["moduleName"] = "software.amazon.smithy.protocoltest.traits"

val smithyVersion: String by project

dependencies {
    testImplementation("org.junit.jupiter:junit-jupiter-api:5.4.0")
    testRuntimeOnly("org.junit.jupiter:junit-jupiter-engine:5.4.0")
    testImplementation("org.junit.jupiter:junit-jupiter-params:5.4.0")
    testImplementation("org.hamcrest:hamcrest:2.1")
    implementation("software.amazon.smithy:smithy-codegen-core:$smithyVersion")
}
