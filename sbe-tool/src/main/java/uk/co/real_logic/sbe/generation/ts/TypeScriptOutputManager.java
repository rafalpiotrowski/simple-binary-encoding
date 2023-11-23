/*
 * Copyright 2013-2023 Real Logic Limited.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package uk.co.real_logic.sbe.generation.ts;

import org.agrona.collections.Object2NullableObjectHashMap;
import org.agrona.collections.Object2ObjectHashMap;
import org.agrona.generation.DynamicPackageOutputManager;
import org.agrona.generation.PackageOutputManager;

import java.io.IOException;
import java.io.Writer;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

/**
 * Implementation of {@link DynamicPackageOutputManager} for TypeScript.
 */
public class TypeScriptOutputManager implements DynamicPackageOutputManager
{
    private final File outputDir;

    private final String baseDirName;
    private final PackageOutputManager initialPackageOutputManager;
    private PackageOutputManager actingPackageOutputManager;
    private final Object2ObjectHashMap<String, PackageOutputManager> outputManagerByPackageName
        = new Object2NullableObjectHashMap<>();

    /**
     * Constructor.
     *
     * @param baseDirName the target directory
     * @param packageName the initial package name
     */
    public TypeScriptOutputManager(final String baseDirName, final String packageName)
    {
        initialPackageOutputManager = new PackageOutputManager(baseDirName, packageName);
        actingPackageOutputManager = initialPackageOutputManager;
        this.baseDirName = baseDirName;

        outputDir = new File(baseDirName);
        if (!outputDir.exists() && !outputDir.mkdirs())
        {
            throw new IllegalStateException("Unable to create directory: " + baseDirName);
        }
    }

    /**
     * {@inheritDoc}
     */
    public void setPackageName(final String packageName)
    {
        actingPackageOutputManager = outputManagerByPackageName.get(packageName);
        if (actingPackageOutputManager == null)
        {
            actingPackageOutputManager = new PackageOutputManager(baseDirName, packageName);
            outputManagerByPackageName.put(packageName, actingPackageOutputManager);
        }
    }

    private void resetPackage()
    {
        actingPackageOutputManager = initialPackageOutputManager;
    }

    /**
     * {@inheritDoc}
     */
    public Writer createOutput(final String name) throws IOException
    {
        final File targetFile = new File(outputDir, name + ".g.ts");
        return Files.newBufferedWriter(targetFile.toPath(), StandardCharsets.UTF_8);
    }

}
