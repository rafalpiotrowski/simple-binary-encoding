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

// import org.agrona.DirectBuffer;
// import org.agrona.MutableDirectBuffer;
import org.agrona.Strings;
import org.agrona.Verify;
import org.agrona.generation.DynamicPackageOutputManager;
import org.agrona.sbe.*;
import uk.co.real_logic.sbe.PrimitiveType;
import uk.co.real_logic.sbe.generation.CodeGenerator;
import uk.co.real_logic.sbe.generation.Generators;
import uk.co.real_logic.sbe.ir.*;

import java.io.IOException;
import java.io.Writer;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Formatter;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Function;

// import static uk.co.real_logic.sbe.SbeTool.JAVA_INTERFACE_PACKAGE;
import static uk.co.real_logic.sbe.generation.ts.TypeScriptGenerator.CodecType.DECODER;
import static uk.co.real_logic.sbe.generation.ts.TypeScriptGenerator.CodecType.ENCODER;
import static uk.co.real_logic.sbe.generation.ts.TypeScriptUtil.*;
import static uk.co.real_logic.sbe.ir.GenerationUtil.*;

/**
 * Generate codecs for the Java 8 programming language.
 */
@SuppressWarnings("MethodLength")
public class TypeScriptGenerator implements CodeGenerator
{
    static final String MESSAGE_HEADER_ENCODER_TYPE = "MessageHeaderEncoder";
    static final String MESSAGE_HEADER_DECODER_TYPE = "MessageHeaderDecoder";

    enum CodecType
    {
        DECODER,
        ENCODER
    }

    private static final String META_ATTRIBUTE_ENUM = "MetaAttribute";
    private static final String PACKAGE_INFO = "package-info";
    private static final String BASE_INDENT = "";
    private static final String INDENT = "    ";
    private static final Set<String> PACKAGES_EMPTY_SET = Collections.emptySet();

    private final Ir ir;
    private final DynamicPackageOutputManager outputManager;
    private final String fqMutableBuffer;
    private final String mutableBuffer;
    private final String fqReadOnlyBuffer;
    private final String readOnlyBuffer;
    private final boolean shouldGenerateGroupOrderAnnotation;
    private final boolean shouldGenerateInterfaces;
    private final boolean shouldDecodeUnknownEnumValues;
    private final boolean shouldSupportTypesPackageNames;
    private final Set<String> packageNameByTypes = new HashSet<>();

    /**
     * Create a new TypeScript language {@link CodeGenerator}.
     *
     * @param ir            for the messages and types.
     * @param outputManager for generating the codecs to.
     */
    public TypeScriptGenerator(final Ir ir, final DynamicPackageOutputManager outputManager)
    {
        this(
            ir,
            "MutableDirectBuffer",
            "DirectBuffer",
            false,
            false,
            false,
            outputManager);
    }

    /**
     * Create a new Java language {@link CodeGenerator}. Generator support for types in their own package is disabled.
     *
     * @param ir                                 for the messages and types.
     * @param mutableBuffer                      implementation used for mutating underlying buffers.
     * @param readOnlyBuffer                     implementation used for reading underlying buffers.
     * @param shouldGenerateGroupOrderAnnotation in the codecs.
     * @param shouldGenerateInterfaces           for common methods.
     * @param shouldDecodeUnknownEnumValues      generate support for unknown enum values when decoding.
     * @param outputManager                      for generating the codecs to.
     */
    public TypeScriptGenerator(
        final Ir ir,
        final String mutableBuffer,
        final String readOnlyBuffer,
        final boolean shouldGenerateGroupOrderAnnotation,
        final boolean shouldGenerateInterfaces,
        final boolean shouldDecodeUnknownEnumValues,
        final DynamicPackageOutputManager outputManager)
    {
        this(ir, mutableBuffer, readOnlyBuffer, shouldGenerateGroupOrderAnnotation, shouldGenerateInterfaces,
            shouldDecodeUnknownEnumValues, false, outputManager);
    }

    /**
     * Create a new Java language {@link CodeGenerator}.
     *
     * @param ir                                 for the messages and types.
     * @param mutableBuffer                      implementation used for mutating underlying buffers.
     * @param readOnlyBuffer                     implementation used for reading underlying buffers.
     * @param shouldGenerateGroupOrderAnnotation in the codecs.
     * @param shouldGenerateInterfaces           for common methods.
     * @param shouldDecodeUnknownEnumValues      generate support for unknown enum values when decoding.
     * @param shouldSupportTypesPackageNames     generator support for types in their own package.
     * @param outputManager                      for generating the codecs to.
     */
    public TypeScriptGenerator(
        final Ir ir,
        final String mutableBuffer,
        final String readOnlyBuffer,
        final boolean shouldGenerateGroupOrderAnnotation,
        final boolean shouldGenerateInterfaces,
        final boolean shouldDecodeUnknownEnumValues,
        final boolean shouldSupportTypesPackageNames,
        final DynamicPackageOutputManager outputManager)
    {
        Verify.notNull(ir, "ir");
        Verify.notNull(outputManager, "outputManager");

        this.ir = ir;
        this.shouldSupportTypesPackageNames = shouldSupportTypesPackageNames;
        this.outputManager = outputManager;

        // this.mutableBuffer = validateBufferImplementation(mutableBuffer, MutableDirectBuffer.class);
        this.mutableBuffer = mutableBuffer;
        this.fqMutableBuffer = mutableBuffer;

        // this.readOnlyBuffer = validateBufferImplementation(readOnlyBuffer, DirectBuffer.class);
        this.readOnlyBuffer = readOnlyBuffer;
        this.fqReadOnlyBuffer = readOnlyBuffer;

        this.shouldGenerateGroupOrderAnnotation = shouldGenerateGroupOrderAnnotation;
        this.shouldGenerateInterfaces = shouldGenerateInterfaces;
        this.shouldDecodeUnknownEnumValues = shouldDecodeUnknownEnumValues;
    }

    /**
     * Generate the composites for dealing with the message header.
     *
     * @throws IOException if an error is encountered when writing the output.
     */
    public void generateMessageHeaderStub() throws IOException
    {
        generateComposite(ir.headerStructure().tokens());
    }

    /**
     * Generate the stubs for the types used as message fields.
     *
     * @throws IOException if an error is encountered when writing the output.
     */
    public void generateTypeStubs() throws IOException
    {
        generateMetaAttributeEnum();

        for (final List<Token> tokens : ir.types())
        {
            switch (tokens.get(0).signal())
            {
                case BEGIN_ENUM:
                    generateEnum(tokens);
                    break;

                case BEGIN_SET:
                    generateBitSet(tokens);
                    break;

                case BEGIN_COMPOSITE:
                    generateComposite(tokens);
                    break;

                default:
                    break;
            }
        }
    }

    /**
     * Register the types explicit package - if set and should be supported.
     *
     * @param token the 0-th token of the type.
     * @param ir    the intermediate representation.
     * @return the overridden package name of the type if set and supported, or {@link Ir#applicableNamespace()}.
     */
    private String registerTypesPackageName(final Token token, final Ir ir)
    {
        if (!shouldSupportTypesPackageNames)
        {
            return ir.applicableNamespace();
        }

        if (token.packageName() != null)
        {
            packageNameByTypes.add(token.packageName());
            outputManager.setPackageName(token.packageName());
            return token.packageName();
        }

        return ir.applicableNamespace();
    }

    /**
     * {@inheritDoc}
     */
    public void generate() throws IOException
    {
        packageNameByTypes.clear();
        generatePackageInfo();
        generateTypeStubs();
        generateMessageHeaderStub();

        for (final List<Token> tokens : ir.messages())
        {
            final Token msgToken = tokens.get(0);
            final List<Token> messageBody = getMessageBody(tokens);
            final boolean hasVarData = -1 != findSignal(messageBody, Signal.BEGIN_VAR_DATA);

            int i = 0;
            final List<Token> fields = new ArrayList<>();
            i = collectFields(messageBody, i, fields);

            final List<Token> groups = new ArrayList<>();
            i = collectGroups(messageBody, i, groups);

            final List<Token> varData = new ArrayList<>();
            collectVarData(messageBody, i, varData);

            generateDecoder(msgToken, fields, groups, varData, hasVarData);
            generateEncoder(msgToken, fields, groups, varData, hasVarData);
        }
    }

    private void generateEncoder(
        final Token msgToken,
        final List<Token> fields,
        final List<Token> groups,
        final List<Token> varData,
        final boolean hasVarData)
        throws IOException
    {
        final String className = formatClassName(encoderName(msgToken.name()));
        final String implementsString = implementsInterface(MessageEncoderFlyweight.class.getSimpleName());

        try (Writer out = outputManager.createOutput(className))
        {
            out.append(generateMainHeader(ir.applicableNamespace(), ENCODER, hasVarData));

            if (shouldGenerateGroupOrderAnnotation)
            {
                generateAnnotations(BASE_INDENT, className, groups, out, this::encoderName);
            }
            out.append(generateDeclaration(className, implementsString, msgToken));
            out.append(generateEncoderFlyweightCode(className, msgToken));

            final StringBuilder sb = new StringBuilder();
            generateEncoderFields(sb, className, fields, BASE_INDENT);
            generateEncoderGroups(sb, className, groups, BASE_INDENT, false);
            generateEncoderVarData(sb, className, varData, BASE_INDENT);

            generateEncoderDisplay(sb, decoderName(msgToken.name()));

            out.append(sb);
            out.append("}\n");
        }
    }

    private void generateDecoder(
        final Token msgToken,
        final List<Token> fields,
        final List<Token> groups,
        final List<Token> varData,
        final boolean hasVarData)
        throws IOException
    {
        final String className = formatClassName(decoderName(msgToken.name()));
        final String implementsString = implementsInterface(MessageDecoderFlyweight.class.getSimpleName());

        try (Writer out = outputManager.createOutput(className))
        {
            out.append(generateMainHeader(ir.applicableNamespace(), DECODER, hasVarData));

            if (shouldGenerateGroupOrderAnnotation)
            {
                generateAnnotations(BASE_INDENT, className, groups, out, this::decoderName);
            }
            out.append(generateDeclaration(className, implementsString, msgToken));
            out.append(generateDecoderFlyweightCode(className, msgToken));

            final StringBuilder sb = new StringBuilder();
            generateDecoderFields(sb, fields, BASE_INDENT);
            generateDecoderGroups(sb, className, groups, BASE_INDENT, false);
            generateDecoderVarData(sb, varData, BASE_INDENT);

            generateDecoderDisplay(sb, msgToken.name(), fields, groups, varData);
            generateMessageLength(sb, className, true, groups, varData, BASE_INDENT);

            out.append(sb);
            out.append("}\n");
        }
    }

    private void generateDecoderGroups(
        final StringBuilder sb,
        final String outerClassName,
        final List<Token> tokens,
        final String indent,
        final boolean isSubGroup) throws IOException
    {
        for (int i = 0, size = tokens.size(); i < size; i++)
        {
            final Token groupToken = tokens.get(i);
            if (groupToken.signal() != Signal.BEGIN_GROUP)
            {
                throw new IllegalStateException("tokens must begin with BEGIN_GROUP: token=" + groupToken);
            }

            final int index = i;
            final String groupName = decoderName(formatClassName(groupToken.name()));

            ++i;
            final int groupHeaderTokenCount = tokens.get(i).componentTokenCount();
            i += groupHeaderTokenCount;

            final List<Token> fields = new ArrayList<>();
            i = collectFields(tokens, i, fields);

            final List<Token> groups = new ArrayList<>();
            i = collectGroups(tokens, i, groups);

            final List<Token> varData = new ArrayList<>();
            i = collectVarData(tokens, i, varData);

            generateGroupDecoderProperty(sb, groupName, groupToken, indent, isSubGroup);
            generateTypeJavadoc(sb, indent + INDENT, groupToken);

            if (shouldGenerateGroupOrderAnnotation)
            {
                generateAnnotations(indent + INDENT, groupName, groups, sb, this::decoderName);
            }
            generateGroupDecoderClassHeader(sb, groupName, outerClassName, tokens, groups, index, indent + INDENT);

            generateDecoderFields(sb, fields, indent + INDENT);
            generateDecoderGroups(sb, outerClassName, groups, indent + INDENT, true);
            generateDecoderVarData(sb, varData, indent + INDENT);

            appendGroupInstanceDecoderDisplay(sb, fields, groups, varData, indent + INDENT);
            generateMessageLength(sb, groupName, false, groups, varData, indent + INDENT);

            sb.append(indent).append("    }\n");
        }
    }

    private void generateEncoderGroups(
        final StringBuilder sb,
        final String outerClassName,
        final List<Token> tokens,
        final String indent,
        final boolean isSubGroup) throws IOException
    {
        for (int i = 0, size = tokens.size(); i < size; i++)
        {
            final Token groupToken = tokens.get(i);
            if (groupToken.signal() != Signal.BEGIN_GROUP)
            {
                throw new IllegalStateException("tokens must begin with BEGIN_GROUP: token=" + groupToken);
            }

            final int index = i;
            final String groupName = groupToken.name();
            final String groupClassName = encoderName(groupName);

            ++i;
            final int groupHeaderTokenCount = tokens.get(i).componentTokenCount();
            i += groupHeaderTokenCount;

            final List<Token> fields = new ArrayList<>();
            i = collectFields(tokens, i, fields);

            final List<Token> groups = new ArrayList<>();
            i = collectGroups(tokens, i, groups);

            final List<Token> varData = new ArrayList<>();
            i = collectVarData(tokens, i, varData);

            generateGroupEncoderProperty(sb, groupName, groupToken, indent, isSubGroup);
            generateTypeJavadoc(sb, indent + INDENT, groupToken);

            if (shouldGenerateGroupOrderAnnotation)
            {
                generateAnnotations(indent + INDENT, groupClassName, groups, sb, this::encoderName);
            }
            generateGroupEncoderClassHeader(sb, groupName, outerClassName, tokens, groups, index, indent + INDENT);

            generateEncoderFields(sb, groupClassName, fields, indent + INDENT);
            generateEncoderGroups(sb, outerClassName, groups, indent + INDENT, true);
            generateEncoderVarData(sb, groupClassName, varData, indent + INDENT);

            sb.append(indent).append("    }\n");
        }
    }

    private void generateGroupDecoderClassHeader(
        final StringBuilder sb,
        final String groupName,
        final String parentMessageClassName,
        final List<Token> tokens,
        final List<Token> subGroupTokens,
        final int index,
        final String indent)
    {
        final String className = formatClassName(groupName);
        final int dimensionHeaderLen = tokens.get(index + 1).encodedLength();

        final Token blockLengthToken = Generators.findFirst("blockLength", tokens, index);
        final Token numInGroupToken = Generators.findFirst("numInGroup", tokens, index);

        final PrimitiveType blockLengthType = blockLengthToken.encoding().primitiveType();
        final String blockLengthOffset = "limit + " + blockLengthToken.offset();
        final String blockLengthGet = generateGet(
            blockLengthType, blockLengthOffset, byteOrderString(blockLengthToken.encoding()));

        final PrimitiveType numInGroupType = numInGroupToken.encoding().primitiveType();
        final String numInGroupOffset = "limit + " + numInGroupToken.offset();
        final String numInGroupGet = generateGet(
            numInGroupType, numInGroupOffset, byteOrderString(numInGroupToken.encoding()));

        generateGroupDecoderClassDeclaration(
            sb,
            groupName,
            parentMessageClassName,
            findSubGroupNames(subGroupTokens),
            indent,
            dimensionHeaderLen);

        final String blockLenCast = PrimitiveType.UINT32 == blockLengthType ? "(int)" : "";
        final String numInGroupCast = PrimitiveType.UINT32 == numInGroupType ? "(int)" : "";

        sb.append("\n")
            .append(indent).append("    public wrap(buffer: ").append(readOnlyBuffer).append(")\n")
            .append(indent).append("    {\n")
            .append(indent).append("        if (buffer != this.buffer)\n")
            .append(indent).append("        {\n")
            .append(indent).append("            this.buffer = buffer;\n")
            .append(indent).append("        }\n\n")
            .append(indent).append("        let index: number = 0;\n")
            .append(indent).append("        let limit = this._parentMessage.getLimit();\n")
            .append(indent).append("        this._parentMessage.setLimit(limit + ")
            .append(parentMessageClassName).append(".HEADER_SIZE);\n")
            .append(indent).append("        this._blockLength = ")
            .append(blockLenCast).append(blockLengthGet).append(";\n")
            .append(indent).append("        count = ").append(numInGroupCast).append(numInGroupGet).append(";\n")
            .append(indent).append("    }\n");

        sb.append("\n")
            .append(indent).append("    public ").append("next(): ").append(className).append("\n")
            .append(indent).append("    {\n")
            .append(indent).append("        if (index >= count)\n")
            .append(indent).append("        {\n")
            .append(indent).append("            throw new Error(\"No such element\");\n")
            .append(indent).append("        }\n\n")
            .append(indent).append("        this._offset = this._parentMessage.getLimit();\n")
            .append(indent).append("        this._parentMessage.setLimit(this._offset + this.blockLength);\n")
            .append(indent).append("        ++index;\n\n")
            .append(indent).append("        return this;\n")
            .append(indent).append("    }\n");

        final String numInGroupJavaTypeName = typeScriptTypeName(numInGroupType);
        final String numInGroupMinValue = generateLiteral(
            numInGroupType, numInGroupToken.encoding().applicableMinValue().toString());
        generatePrimitiveFieldMetaMethod(sb, indent, numInGroupJavaTypeName, "count", "Min", numInGroupMinValue);
        final String numInGroupMaxValue = generateLiteral(
            numInGroupType, numInGroupToken.encoding().applicableMaxValue().toString());
        generatePrimitiveFieldMetaMethod(sb, indent, numInGroupJavaTypeName, "count", "Max", numInGroupMaxValue);

        sb.append("\n")
            .append(indent).append("    public static sbeHeaderSize() : number\n")
            .append(indent).append("    {\n")
            .append(indent).append("        return " + parentMessageClassName + ".HEADER_SIZE;\n")
            .append(indent).append("    }\n");

        sb.append("\n")
            .append(indent).append("    public static sbeBlockLength(): number\n")
            .append(indent).append("    {\n")
            .append(indent).append("        return ").append(tokens.get(index).encodedLength()).append(";\n")
            .append(indent).append("    }\n");

        sb.append("\n")
            .append(indent).append("    public actingBlockLength(): number\n")
            .append(indent).append("    {\n")
            .append(indent).append("        return this._blockLength;\n")
            .append(indent).append("    }\n\n")
            .append(indent).append("    public count(): number\n")
            .append(indent).append("    {\n")
            .append(indent).append("        return this._count;\n")
            .append(indent).append("    }\n\n")
            .append(indent).append("    public iterator(): Iterator<").append(className).append(">\n")
            .append(indent).append("    {\n")
            .append(indent).append("        return this;\n")
            .append(indent).append("    }\n\n")
            .append(indent).append("    public remove()\n")
            .append(indent).append("    {\n")
            .append(indent).append("        throw new Error(\"UnsupportedOperationException\");\n")
            .append(indent).append("    }\n\n")
            .append(indent).append("    public hasNext(): boolean\n")
            .append(indent).append("    {\n")
            .append(indent).append("        return this._index < this._count;\n")
            .append(indent).append("    }\n");
    }

    private void generateGroupEncoderClassHeader(
        final StringBuilder sb,
        final String groupName,
        final String parentMessageClassName,
        final List<Token> tokens,
        final List<Token> subGroupTokens,
        final int index,
        final String ind)
    {
        final int dimensionHeaderSize = tokens.get(index + 1).encodedLength();

        generateGroupEncoderClassDeclaration(
            sb,
            groupName,
            parentMessageClassName,
            findSubGroupNames(subGroupTokens),
            ind,
            dimensionHeaderSize);

        final int blockLength = tokens.get(index).encodedLength();
        final Token blockLengthToken = Generators.findFirst("blockLength", tokens, index);
        final Token numInGroupToken = Generators.findFirst("numInGroup", tokens, index);

        final PrimitiveType blockLengthType = blockLengthToken.encoding().primitiveType();
        final String blockLengthOffset = "limit + " + blockLengthToken.offset();
        final String blockLengthValue = Integer.toString(blockLength);
        final String blockLengthPut = generatePut(
            blockLengthType, blockLengthOffset, blockLengthValue, byteOrderString(blockLengthToken.encoding()));

        final PrimitiveType numInGroupType = numInGroupToken.encoding().primitiveType();

        final PrimitiveType numInGroupTypeCast = PrimitiveType.UINT32 == numInGroupType ?
            PrimitiveType.INT32 : numInGroupType;
        final String numInGroupOffset = "limit + " + numInGroupToken.offset();
        final String numInGroupValue = "count";
        final String numInGroupPut = generatePut(
            numInGroupTypeCast, numInGroupOffset, numInGroupValue, byteOrderString(numInGroupToken.encoding()));

        new Formatter(sb).format("\n" +
            ind + "    public wrap(buffer: %2$s, count: number)\n" +
            ind + "    {\n" +
            ind + "        if (count < %3$d || count > %4$d)\n" +
            ind + "        {\n" +
            ind + "            throw new Error(\"count outside allowed range: count=\" + count);\n" +
            ind + "        }\n\n" +
            ind + "        if (buffer != this._buffer)\n" +
            ind + "        {\n" +
            ind + "            this._buffer = buffer;\n" +
            ind + "        }\n\n" +
            ind + "        this._index: number = 0;\n" +
            ind + "        this._count = count;\n" +
            ind + "        let limit = this._parentMessage.getLimit();\n" +
            ind + "        this._initialLimit = limit;\n" +
            ind + "        this._parentMessage.getLimit(limit + " + parentMessageClassName + ".HEADER_SIZE);\n" +
            ind + "        %5$s;\n" +
            ind + "        %6$s;\n" +
            ind + "    }\n",
            parentMessageClassName,
            mutableBuffer,
            numInGroupToken.encoding().applicableMinValue().longValue(),
            numInGroupToken.encoding().applicableMaxValue().longValue(),
            blockLengthPut,
            numInGroupPut);

        sb.append("\n")
            .append(ind).append("    public next(): ").append(encoderName(groupName)).append("\n")
            .append(ind).append("    {\n")
            .append(ind).append("        if (this._index >= this._count)\n")
            .append(ind).append("        {\n")
            .append(ind).append("            throw new Error(\"NoSuchElementException\");\n")
            .append(ind).append("        }\n\n")
            .append(ind).append("        this._offset = this._parentMessage.getLimit();\n")
            .append(ind).append("        this._parentMessage.setLimit(this._offset + this.sbeBlockLength());\n")
            .append(ind).append("        ++this._index;\n\n")
            .append(ind).append("        return this;\n")
            .append(ind).append("    }\n");

        final String countOffset = "initialLimit + " + numInGroupToken.offset();
        final String resetCountPut = generatePut(
            numInGroupTypeCast, countOffset, numInGroupValue, byteOrderString(numInGroupToken.encoding()));

        sb.append("\n")
            .append(ind).append("    public resetCountToIndex(): number\n")
            .append(ind).append("    {\n")
            .append(ind).append("        this._count = this._index;\n")
            .append(ind).append("        ").append(resetCountPut).append(";\n\n")
            .append(ind).append("        return this._count;\n")
            .append(ind).append("    }\n");

        final String numInGroupJavaTypeName = typeScriptTypeName(numInGroupType);
        final String numInGroupMinValue = generateLiteral(
            numInGroupType, numInGroupToken.encoding().applicableMinValue().toString());
        generatePrimitiveFieldMetaMethod(sb, ind, numInGroupJavaTypeName, "count", "Min", numInGroupMinValue);
        final String numInGroupMaxValue = generateLiteral(
            numInGroupType, numInGroupToken.encoding().applicableMaxValue().toString());
        generatePrimitiveFieldMetaMethod(sb, ind, numInGroupJavaTypeName, "count", "Max", numInGroupMaxValue);

        sb.append("\n")
            .append(ind).append("    public static sbeHeaderSize(): number\n")
            .append(ind).append("    {\n")
            .append(ind).append("        return " + parentMessageClassName + ".HEADER_SIZE;\n")
            .append(ind).append("    }\n");

        sb.append("\n")
            .append(ind).append("    public static sbeBlockLength(): number\n")
            .append(ind).append("    {\n")
            .append(ind).append("        return ").append(blockLength).append(";\n")
            .append(ind).append("    }\n");
    }

    private static String primitiveTypeName(final Token token)
    {
        return typeScriptTypeName(token.encoding().primitiveType());
    }

    private void generateGroupDecoderClassDeclaration(
        final StringBuilder sb,
        final String groupName,
        final String parentMessageClassName,
        final List<String> subGroupNames,
        final String indent,
        final int dimensionHeaderSize)
    {
        final String className = formatClassName(groupName);

        new Formatter(sb).format("\n" +
            indent + "public static final class %1$s\n" +
            indent + "    implements Iterable<%1$s>, Iterator<%1$s>\n" +
            indent + "{\n" +
            indent + "    public static final int HEADER_SIZE = %2$d;\n" +
            indent + "    private final %3$s _parentMessage;\n" +
            indent + "    private %4$s _buffer;\n" +
            indent + "    private _count: number = 0;\n" +
            indent + "    private _index: number = 0;\n" +
            indent + "    private _offset: number = 0;\n" +
            indent + "    private _blockLength: number = 0;\n",
            className,
            dimensionHeaderSize,
            parentMessageClassName,
            readOnlyBuffer);

        for (final String subGroupName : subGroupNames)
        {
            final String type = formatClassName(decoderName(subGroupName));
            final String field = formatPropertyName(subGroupName);
            sb.append(indent).append("    private final ").append(type).append(" ").append(field).append(";\n");
        }

        sb
            .append("\n")
            .append(indent).append("    ")
            .append(className).append("(final ").append(parentMessageClassName).append(" parentMessage)\n")
            .append(indent).append("    {\n")
            .append(indent).append("        this._parentMessage = parentMessage;\n");

        for (final String subGroupName : subGroupNames)
        {
            final String type = formatClassName(decoderName(subGroupName));
            final String field = formatPropertyName(subGroupName);
            sb
                .append(indent).append("        ")
                .append(field).append(" = new ").append(type).append("(parentMessage);\n");
        }

        sb.append(indent).append("    }\n");
    }

    private void generateGroupEncoderClassDeclaration(
        final StringBuilder sb,
        final String groupName,
        final String parentMessageClassName,
        final List<String> subGroupNames,
        final String indent,
        final int dimensionHeaderSize)
    {
        final String className = encoderName(groupName);

        new Formatter(sb).format("\n" +
            indent + "public static final class %1$s\n" +
            indent + "{\n" +
            indent + "    public static final int HEADER_SIZE = %2$d;\n" +
            indent + "    private final %3$s _parentMessage;\n" +
            indent + "    private %4$s _buffer;\n" +
            indent + "    private _count: number = 0;\n" +
            indent + "    private _index: number = 0;\n" +
            indent + "    private _offset: number = 0;\n" +
            indent + "    private _initialLimit: number = 0;\n",
            className,
            dimensionHeaderSize,
            parentMessageClassName,
            mutableBuffer);

        for (final String subGroupName : subGroupNames)
        {
            final String type = encoderName(subGroupName);
            final String field = formatPropertyName(subGroupName);
            sb.append(indent).append("    private final ").append(type).append(" ").append(field).append(";\n");
        }

        sb
            .append("\n")
            .append(indent).append("    ")
            .append(className).append("(final ").append(parentMessageClassName).append(" parentMessage)\n")
            .append(indent).append("    {\n")
            .append(indent).append("        this._parentMessage = parentMessage;\n");

        for (final String subGroupName : subGroupNames)
        {
            final String type = encoderName(subGroupName);
            final String field = formatPropertyName(subGroupName);
            sb
                .append(indent).append("        ")
                .append(field).append(" = new ").append(type).append("(parentMessage);\n");
        }

        sb.append(indent).append("    }\n");
    }

    private static void generateGroupDecoderProperty(
        final StringBuilder sb,
        final String groupName,
        final Token token,
        final String indent,
        final boolean isSubGroup)
    {
        final String className = formatClassName(groupName);
        final String propertyName = formatPropertyName(token.name());

        if (!isSubGroup)
        {
            new Formatter(sb).format("\n" +
                indent + "    private _%s: %s = new %s(this);\n",
                propertyName,
                className,
                className);
        }

        new Formatter(sb).format("\n" +
            indent + "    public static %sId(): bigint\n" +
            indent + "    {\n" +
            indent + "        return %d" + "n;\n" +
            indent + "    }\n",
            formatPropertyName(groupName),
            token.id());

        new Formatter(sb).format("\n" +
            indent + "    public static %sSinceVersion(): number\n" +
            indent + "    {\n" +
            indent + "        return %d" + "n;\n" +
            indent + "    }\n",
            formatPropertyName(groupName),
            token.version());

        final String actingVersionGuard = token.version() == 0 ?
            "" :
            indent + "        if (parentMessage._actingVersion < " + token.version() + ")\n" +
            indent + "        {\n" +
            indent + "            " + propertyName + ".count = 0;\n" +
            indent + "            " + propertyName + ".index = 0;\n" +
            indent + "            return " + propertyName + ";\n" +
            indent + "        }\n\n";

        generateFlyweightPropertyJavadoc(sb, indent + INDENT, token, className);
        new Formatter(sb).format("\n" +
            indent + "    public %2$s(): %1$s\n" +
            indent + "    {\n" +
            "%3$s" +
            indent + "        %2$s.wrap(this._buffer);\n" +
            indent + "        return %2$s;\n" +
            indent + "    }\n",
            propertyName,
            className,
            actingVersionGuard);
    }

    private void generateGroupEncoderProperty(
        final StringBuilder sb,
        final String groupName,
        final Token token,
        final String indent,
        final boolean isSubGroup)
    {
        final String className = formatClassName(encoderName(groupName));
        final String propertyName = formatPropertyName(groupName);

        if (!isSubGroup)
        {
            new Formatter(sb).format("\n" +
                indent + "    private _%s: %s = new %s(this);\n",
                propertyName,
                className,
                className);
        }

        new Formatter(sb).format("\n" +
            indent + "    public static %sId(): bigint\n" +
            indent + "    {\n" +
            indent + "        return %d" + "n;\n" +
            indent + "    }\n",
            formatPropertyName(groupName),
            token.id());

        generateGroupEncodePropertyJavadoc(sb, indent + INDENT, token, className);
        new Formatter(sb).format("\n" +
            indent + "    public %2$sCount(count: number): %1$s\n" +
            indent + "    {\n" +
            indent + "        %2$s.wrap(buffer, count);\n" +
            indent + "        return %2$s;\n" +
            indent + "    }\n",
            className,
            propertyName);
    }

    private void generateDecoderVarData(
        final StringBuilder sb, final List<Token> tokens, final String indent)
    {
        for (int i = 0, size = tokens.size(); i < size;)
        {
            final Token token = tokens.get(i);
            if (token.signal() != Signal.BEGIN_VAR_DATA)
            {
                throw new IllegalStateException("tokens must begin with BEGIN_VAR_DATA: token=" + token);
            }

            generateFieldIdMethod(sb, token, indent);
            generateFieldSinceVersionMethod(sb, token, indent);

            final String characterEncoding = tokens.get(i + 3).encoding().characterEncoding();
            generateCharacterEncodingMethod(sb, token.name(), characterEncoding, indent);
            generateFieldMetaAttributeMethod(sb, token, indent);

            final String propertyName = Generators.toUpperFirstChar(token.name());
            final Token lengthToken = tokens.get(i + 2);
            final int sizeOfLengthField = lengthToken.encodedLength();
            final Encoding lengthEncoding = lengthToken.encoding();
            final PrimitiveType lengthType = lengthEncoding.primitiveType();
            final String byteOrderStr = byteOrderString(lengthEncoding);
            final String methodPropName = Generators.toLowerFirstChar(propertyName);

            sb.append("\n")
                .append(indent).append("    public static ").append(methodPropName).append("HeaderLength(): number\n")
                .append(indent).append("    {\n")
                .append(indent).append("        return ").append(sizeOfLengthField).append(";\n")
                .append(indent).append("    }\n");

            sb.append("\n")
                .append(indent).append("    public ").append(methodPropName).append("Length(): number\n")
                .append(indent).append("    {\n")
                .append(generateArrayFieldNotPresentCondition(token.version(), indent))
                .append(indent).append("        let limit: number = this._parentMessage.getLimit();\n")
                .append(indent).append("        return ").append(PrimitiveType.UINT32 == lengthType ? "(number)" : "")
                .append(generateGet(lengthType, "limit", byteOrderStr)).append(";\n")
                .append(indent).append("    }\n");

            generateDataDecodeMethods(
                sb, token, propertyName, sizeOfLengthField, lengthType, byteOrderStr, characterEncoding, indent);

            i += token.componentTokenCount();
        }
    }

    private void generateEncoderVarData(
        final StringBuilder sb, final String className, final List<Token> tokens, final String indent)
    {
        for (int i = 0, size = tokens.size(); i < size;)
        {
            final Token token = tokens.get(i);
            if (token.signal() != Signal.BEGIN_VAR_DATA)
            {
                throw new IllegalStateException("tokens must begin with BEGIN_VAR_DATA: token=" + token);
            }

            generateFieldIdMethod(sb, token, indent);
            final Token varDataToken = Generators.findFirst("varData", tokens, i);
            final String characterEncoding = varDataToken.encoding().characterEncoding();
            generateCharacterEncodingMethod(sb, token.name(), characterEncoding, indent);
            generateFieldMetaAttributeMethod(sb, token, indent);

            final String propertyName = Generators.toUpperFirstChar(token.name());
            final Token lengthToken = Generators.findFirst("length", tokens, i);
            final int sizeOfLengthField = lengthToken.encodedLength();
            final Encoding lengthEncoding = lengthToken.encoding();
            final int maxLengthValue = (int)lengthEncoding.applicableMaxValue().longValue();
            final String byteOrderStr = byteOrderString(lengthEncoding);

            final String methodPropName = Generators.toLowerFirstChar(propertyName);
            sb.append("\n")
                .append(indent).append("    public static ").append(methodPropName).append("HeaderLength(): number\n")
                .append(indent).append("    {\n")
                .append(indent).append("        return ")
                .append(sizeOfLengthField).append(";\n")
                .append(indent).append("    }\n");

            generateDataEncodeMethods(
                sb,
                propertyName,
                sizeOfLengthField,
                maxLengthValue,
                lengthEncoding.primitiveType(),
                byteOrderStr,
                characterEncoding,
                className,
                indent);

            i += token.componentTokenCount();
        }
    }

    private void generateDataDecodeMethods(
        final StringBuilder sb,
        final Token token,
        final String propertyName,
        final int sizeOfLengthField,
        final PrimitiveType lengthType,
        final String byteOrderStr,
        final String characterEncoding,
        final String indent)
    {
        new Formatter(sb).format("\n" +
            indent + "    public skip%1$s(): number\n" +
            indent + "    {\n" +
            "%2$s" +
            indent + "        let headerLength: number = %3$d;\n" +
            indent + "        let limit: number = this._parentMessage.getLimit();\n" +
            indent + "        let dataLength: number = %4$s%5$s;\n" +
            indent + "        let dataOffset: number = limit + headerLength;\n" +
            indent + "        this._parentMessage.setLimit(dataOffset + dataLength);\n\n" +
            indent + "        return dataLength;\n" +
            indent + "    }\n",
            Generators.toUpperFirstChar(propertyName),
            generateStringNotPresentConditionForAppendable(token.version(), indent),
            sizeOfLengthField,
            PrimitiveType.UINT32 == lengthType ? "(number)" : "",
            generateGet(lengthType, "limit", byteOrderStr));

        generateVarDataTypedDecoder(
            sb,
            token,
            propertyName,
            sizeOfLengthField,
            mutableBuffer,
            lengthType,
            byteOrderStr,
            indent);

        generateVarDataTypedDecoder(
            sb,
            token,
            propertyName,
            sizeOfLengthField,
            "byte[]",
            lengthType,
            byteOrderStr,
            indent);

        generateVarDataWrapDecoder(sb, token, propertyName, sizeOfLengthField, lengthType, byteOrderStr, indent);

        if (null != characterEncoding)
        {
            new Formatter(sb).format("\n" +
                indent + "    public %1$s(): string\n" +
                indent + "    {\n" +
                "%2$s" +
                indent + "        let headerLength: number = %3$d;\n" +
                indent + "        let limit: number = this._parentMessage.getLimit();\n" +
                indent + "        let dataLength: number = %4$s%5$s;\n" +
                indent + "        this._parentMessage.setLimit(limit + headerLength + dataLength);\n\n" +
                indent + "        if (0 == dataLength)\n" +
                indent + "        {\n" +
                indent + "            return \"\";\n" +
                indent + "        }\n\n" +
                indent + "        let tmp: number[] = new number[dataLength];\n" +
                indent + "        this._buffer.getBytes(limit + headerLength, tmp, 0, dataLength);\n\n" +
                indent + "        return new String(tmp, %6$s);\n" + //TODO: check if this is correct
                indent + "    }\n",
                formatPropertyName(propertyName),
                generateStringNotPresentCondition(token.version(), indent),
                sizeOfLengthField,
                PrimitiveType.UINT32 == lengthType ? "(number)" : "", //TODO: check if this is correct
                generateGet(lengthType, "limit", byteOrderStr),
                charset(characterEncoding));

            if (isAsciiEncoding(characterEncoding))
            {
                new Formatter(sb).format("\n" +
                    indent + "    public get%1$s(appendable: Appendable): number\n" +
                    indent + "    {\n" +
                    "%2$s" +
                    indent + "        let headerLength: number = %3$d;\n" +
                    indent + "        let limit: number = this._parentMessage.getLimit();\n" +
                    indent + "        let dataLength: number = %4$s%5$s;\n" +
                    indent + "        let dataOffset: number = limit + headerLength;\n\n" +
                    indent + "        this._parentMessage.setLimit(dataOffset + dataLength);\n" +
                    indent + "        buffer.getStringWithoutLengthAscii(dataOffset, dataLength, appendable);\n\n" +
                    indent + "        return dataLength;\n" +
                    indent + "    }\n",
                    Generators.toUpperFirstChar(propertyName),
                    generateStringNotPresentConditionForAppendable(token.version(), indent),
                    sizeOfLengthField,
                    PrimitiveType.UINT32 == lengthType ? "(number)" : "", //todo: check if this is correct
                    generateGet(lengthType, "limit", byteOrderStr));
            }
        }
    }

    private void generateVarDataWrapDecoder(
        final StringBuilder sb,
        final Token token,
        final String propertyName,
        final int sizeOfLengthField,
        final PrimitiveType lengthType,
        final String byteOrderStr,
        final String indent)
    {
        new Formatter(sb).format("\n" +
            indent + "    public wrap%s(wrapBuffer: %s)\n" +
            indent + "    {\n" +
            "%s" +
            indent + "        let headerLength: number = %d;\n" +
            indent + "        let limit: number = this._parentMessage.getLimit();\n" +
            indent + "        let dataLength: number = %s%s;\n" +
            indent + "        this._parentMessage.setLimit(limit + headerLength + dataLength);\n" +
            indent + "        wrapBuffer.wrap(this._buffer, limit + headerLength, dataLength);\n" +
            indent + "    }\n",
            propertyName,
            readOnlyBuffer,
            generateWrapFieldNotPresentCondition(token.version(), indent),
            sizeOfLengthField,
            PrimitiveType.UINT32 == lengthType ? "(number)" : "", //todo: check if this is correct
            generateGet(lengthType, "limit", byteOrderStr));
    }

    private void generateDataEncodeMethods(
        final StringBuilder sb,
        final String propertyName,
        final int sizeOfLengthField,
        final int maxLengthValue,
        final PrimitiveType lengthType,
        final String byteOrderStr,
        final String characterEncoding,
        final String className,
        final String indent)
    {
        generateDataTypedEncoder(
            sb,
            className,
            propertyName,
            sizeOfLengthField,
            maxLengthValue,
            readOnlyBuffer,
            lengthType,
            byteOrderStr,
            indent);

        generateDataTypedEncoder(
            sb,
            className,
            propertyName,
            sizeOfLengthField,
            maxLengthValue,
            "byte[]",
            lengthType,
            byteOrderStr,
            indent);

        if (null != characterEncoding)
        {
            generateCharArrayEncodeMethods(
                sb,
                propertyName,
                sizeOfLengthField,
                maxLengthValue,
                lengthType,
                byteOrderStr,
                characterEncoding,
                className,
                indent);
        }
    }

    private void generateCharArrayEncodeMethods(
        final StringBuilder sb,
        final String propertyName,
        final int sizeOfLengthField,
        final int maxLengthValue,
        final PrimitiveType lengthType,
        final String byteOrderStr,
        final String characterEncoding,
        final String className,
        final String indent)
    {
        final PrimitiveType lengthPutType = PrimitiveType.UINT32 == lengthType ? PrimitiveType.INT32 : lengthType;

        if (isAsciiEncoding(characterEncoding))
        {
            new Formatter(sb).format("\n" +
                indent + "    public %2$s(value: string): %1$s\n" +
                indent + "    {\n" +
                indent + "        let length: number = null == value ? 0 : value.length();\n" +
                indent + "        if (length > %3$d)\n" +
                indent + "        {\n" +
                indent + "            throw new Error(\"length > maxValue for type: \" + length);\n" +
                indent + "        }\n\n" +
                indent + "        let headerLength: number = %4$d;\n" +
                indent + "        let limit: number = this._parentMessage.getLimit();\n" +
                indent + "        this._parentMessage.setLimit(limit + headerLength + length);\n" +
                indent + "        %5$s;\n" +
                indent + "        this._buffer.putStringWithoutLengthAscii(limit + headerLength, value);\n\n" +
                indent + "        return this;\n" +
                indent + "    }\n",
                formatPropertyName(propertyName),
                className,
                maxLengthValue,
                sizeOfLengthField,
                generatePut(lengthPutType, "limit", "length", byteOrderStr));

            new Formatter(sb).format("\n" +
                indent + "    public %2$s(value: CharSequence): %1$s\n" +
                indent + "    {\n" +
                indent + "        let length: number = null == value ? 0 : value.length();\n" +
                indent + "        if (length > %3$d)\n" +
                indent + "        {\n" +
                indent + "            throw new Error(\"length > maxValue for type: \" + length);\n" +
                indent + "        }\n\n" +
                indent + "        let headerLength: number = %4$d;\n" +
                indent + "        let limit: number = this._parentMessage.getLimit();\n" +
                indent + "        this._parentMessage.getLimit(limit + headerLength + length);\n" +
                indent + "        %5$s;\n" +
                indent + "        this._buffer.putStringWithoutLengthAscii(limit + headerLength, value);\n\n" +
                indent + "        return this;\n" +
                indent + "    }\n",
                formatPropertyName(propertyName),
                className,
                maxLengthValue,
                sizeOfLengthField,
                generatePut(lengthPutType, "limit", "length", byteOrderStr));
        }
        else
        {
            new Formatter(sb).format("\n" +
                indent + "    public %2$s(value: String): %1$s\n" +
                indent + "    {\n" +
                indent + "        let bytes: number[] = (null == value || value.isEmpty()) ?" +
                " org.agrona.collections.ArrayUtil.EMPTY_BYTE_ARRAY : value.getBytes(%3$s);\n\n" +
                indent + "        let length: number = bytes.length;\n" +
                indent + "        if (length > %4$d)\n" +
                indent + "        {\n" +
                indent + "            throw new Error(\"length > maxValue for type: \" + length);\n" +
                indent + "        }\n\n" +
                indent + "        let headerLength: number = %5$d;\n" +
                indent + "        let limit: number = this._parentMessage.getLimit();\n" +
                indent + "        this._parentMessage.setLimit(limit + headerLength + length);\n" +
                indent + "        %6$s;\n" +
                indent + "        this._buffer.putBytes(limit + headerLength, bytes, 0, length);\n\n" +
                indent + "        return this;\n" +
                indent + "    }\n",
                formatPropertyName(propertyName),
                className,
                charset(characterEncoding),
                maxLengthValue,
                sizeOfLengthField,
                generatePut(lengthPutType, "limit", "length", byteOrderStr));
        }
    }

    private void generateVarDataTypedDecoder(
        final StringBuilder sb,
        final Token token,
        final String propertyName,
        final int sizeOfLengthField,
        final String exchangeType,
        final PrimitiveType lengthType,
        final String byteOrderStr,
        final String indent)
    {
        new Formatter(sb).format("\n" +
            indent + "    public get%s(dst: %s, dstOffset: number, length: number): number\n" +
            indent + "    {\n" +
            "%s" +
            indent + "        let headerLength: number = %d;\n" +
            indent + "        let limit: number = this._parentMessage.getLimit();\n" +
            indent + "        let dataLength: number = %s%s;\n" +
            indent + "        let bytesCopied: number = Math.min(length, dataLength);\n" +
            indent + "        this._parentMessage.setLimit(limit + headerLength + dataLength);\n" +
            indent + "        this._buffer.getBytes(limit + headerLength, dst, dstOffset, bytesCopied);\n\n" +
            indent + "        return bytesCopied;\n" +
            indent + "    }\n",
            propertyName,
            exchangeType,
            generateArrayFieldNotPresentCondition(token.version(), indent),
            sizeOfLengthField,
            PrimitiveType.UINT32 == lengthType ? "(number)" : "", //todo: check if this is correct
            generateGet(lengthType, "limit", byteOrderStr));
    }

    private void generateDataTypedEncoder(
        final StringBuilder sb,
        final String className,
        final String propertyName,
        final int sizeOfLengthField,
        final int maxLengthValue,
        final String exchangeType,
        final PrimitiveType lengthType,
        final String byteOrderStr,
        final String indent)
    {
        final PrimitiveType lengthPutType = PrimitiveType.UINT32 == lengthType ? PrimitiveType.INT32 : lengthType;

        new Formatter(sb).format("\n" +
            indent + "    public put%2$s(src: %3$s, srcOffset: number, length: number): %1$s\n" +
            indent + "    {\n" +
            indent + "        if (length > %4$d)\n" +
            indent + "        {\n" +
            indent + "            throw new Error(\"length > maxValue for type: \" + length);\n" +
            indent + "        }\n\n" +
            indent + "        let headerLength: number = %5$d;\n" +
            indent + "        let limit: number = this._parentMessage.getLimit();\n" +
            indent + "        this._parentMessage.setLimit(limit + headerLength + length);\n" +
            indent + "        %6$s;\n" +
            indent + "        this._buffer.putBytes(limit + headerLength, src, srcOffset, length);\n\n" +
            indent + "        return this;\n" +
            indent + "    }\n",
            propertyName,
            className,
            exchangeType,
            maxLengthValue,
            sizeOfLengthField,
            generatePut(lengthPutType, "limit", "length", byteOrderStr));
    }

    private void generateBitSet(final List<Token> tokens) throws IOException
    {
        final Token token = tokens.get(0);
        final String bitSetName = token.applicableTypeName();
        final String decoderName = decoderName(bitSetName);
        final String encoderName = encoderName(bitSetName);
        final List<Token> choiceList = tokens.subList(1, tokens.size() - 1);
        final String implementsString = implementsInterface(Flyweight.class.getSimpleName());

        registerTypesPackageName(token, ir);
        try (Writer out = outputManager.createOutput(decoderName))
        {
            final Encoding encoding = token.encoding();
            generateFixedFlyweightHeader(
                out, token, decoderName, implementsString, readOnlyBuffer, fqReadOnlyBuffer, PACKAGES_EMPTY_SET);
            out.append(generateChoiceIsEmpty(encoding.primitiveType()));

            new Formatter(out).format(
                "\n" +
                "    public getRaw(): %s\n" +
                "    {\n" +
                "        return %s;\n" +
                "    }\n",
                primitiveTypeName(token),
                generateGet(encoding.primitiveType(), "offset", byteOrderString(encoding)));

            generateChoiceDecoders(out, choiceList);
            out.append(generateChoiceDisplay(choiceList));
            out.append("}\n");
        }

        registerTypesPackageName(token, ir);
        try (Writer out = outputManager.createOutput(encoderName))
        {
            generateFixedFlyweightHeader(
                out, token, encoderName, implementsString, mutableBuffer, fqMutableBuffer, PACKAGES_EMPTY_SET);
            generateChoiceClear(out, encoderName, token);
            generateChoiceEncoders(out, encoderName, choiceList);
            out.append("}\n");
        }
    }

    private void generateFixedFlyweightHeader(
        final Writer out,
        final Token token,
        final String typeName,
        final String implementsString,
        final String buffer,
        final String fqBuffer,
        final Set<String> importedTypesPackages) throws IOException
    {
        out.append(generateFileHeader(registerTypesPackageName(token, ir), importedTypesPackages, fqBuffer));
        out.append(generateDeclaration(typeName, implementsString, token));
        out.append(generateFixedFlyweightCode(typeName, token.encodedLength(), buffer));
    }

    private void generateCompositeFlyweightHeader(
        final Token token,
        final String typeName,
        final Writer out,
        final String buffer,
        final String fqBuffer,
        final String implementsString,
        final Set<String> importedTypesPackages) throws IOException
    {
        out.append(generateFileHeader(registerTypesPackageName(token, ir), importedTypesPackages, fqBuffer));
        out.append(generateDeclaration(typeName, implementsString, token));
        out.append(generateFixedFlyweightCode(typeName, token.encodedLength(), buffer));
    }

    private void generateEnum(final List<Token> tokens) throws IOException
    {
        final Token enumToken = tokens.get(0);
        final String enumName = formatClassName(enumToken.applicableTypeName());
        final Encoding encoding = enumToken.encoding();
        final String nullVal = encoding.applicableNullValue().toString();
        final String packageName = registerTypesPackageName(enumToken, ir);

        try (Writer out = outputManager.createOutput(enumName))
        {
            out.append(generateEnumFileHeader(packageName));
            out.append(generateEnumDeclaration(enumName, enumToken));

            final List<Token> valuesList = tokens.subList(1, tokens.size() - 1);
            out.append(generateEnumValues(valuesList, generateLiteral(encoding.primitiveType(), nullVal)));
            out.append(generateEnumBody(enumToken, enumName));

            out.append(generateEnumLookupMethod(valuesList, enumName, nullVal));

            out.append("}\n");
        }
    }

    private void generateComposite(final List<Token> tokens) throws IOException
    {
        final Token token = tokens.get(0);
        final String compositeName = token.applicableTypeName();
        final String decoderName = decoderName(compositeName);
        final String encoderName = encoderName(compositeName);

        registerTypesPackageName(token, ir);
        final Set<String> importedTypesPackages = scanPackagesToImport(tokens);

        try (Writer out = outputManager.createOutput(decoderName))
        {
            final String implementsString = implementsInterface(CompositeDecoderFlyweight.class.getSimpleName());
            generateCompositeFlyweightHeader(
                token, decoderName, out, readOnlyBuffer, fqReadOnlyBuffer, implementsString, importedTypesPackages);

            for (int i = 1, end = tokens.size() - 1; i < end;)
            {
                final Token encodingToken = tokens.get(i);
                final String propertyName = formatPropertyName(encodingToken.name());
                final String typeName = decoderName(encodingToken.applicableTypeName());

                final StringBuilder sb = new StringBuilder();
                generateEncodingOffsetMethod(sb, propertyName, encodingToken.offset(), BASE_INDENT);
                generateEncodingLengthMethod(sb, propertyName, encodingToken.encodedLength(), BASE_INDENT);
                generateFieldSinceVersionMethod(sb, encodingToken, BASE_INDENT);

                switch (encodingToken.signal())
                {
                    case ENCODING:
                        generatePrimitiveDecoder(
                            sb, true, encodingToken.name(), encodingToken, encodingToken, BASE_INDENT);
                        break;

                    case BEGIN_ENUM:
                        generateEnumDecoder(sb, true, encodingToken, propertyName, encodingToken, BASE_INDENT);
                        break;

                    case BEGIN_SET:
                        generateBitSetProperty(
                            sb, true, DECODER, propertyName, encodingToken, encodingToken, BASE_INDENT, typeName);
                        break;

                    case BEGIN_COMPOSITE:
                        generateCompositeProperty(
                            sb, true, DECODER, propertyName, encodingToken, encodingToken, BASE_INDENT, typeName);
                        break;

                    default:
                        break;
                }

                out.append(sb);
                i += encodingToken.componentTokenCount();
            }

            out.append(generateCompositeDecoderDisplay(tokens));

            out.append("}\n");
        }

        registerTypesPackageName(token, ir);
        try (Writer out = outputManager.createOutput(encoderName))
        {
            final String implementsString = implementsInterface(CompositeEncoderFlyweight.class.getSimpleName());
            generateCompositeFlyweightHeader(
                token, encoderName, out, mutableBuffer, fqMutableBuffer, implementsString, importedTypesPackages);

            for (int i = 1, end = tokens.size() - 1; i < end;)
            {
                final Token encodingToken = tokens.get(i);
                final String propertyName = formatPropertyName(encodingToken.name());
                final String typeName = encoderName(encodingToken.applicableTypeName());

                final StringBuilder sb = new StringBuilder();
                generateEncodingOffsetMethod(sb, propertyName, encodingToken.offset(), BASE_INDENT);
                generateEncodingLengthMethod(sb, propertyName, encodingToken.encodedLength(), BASE_INDENT);

                switch (encodingToken.signal())
                {
                    case ENCODING:
                        generatePrimitiveEncoder(sb, encoderName, encodingToken.name(), encodingToken, BASE_INDENT);
                        break;

                    case BEGIN_ENUM:
                        generateEnumEncoder(sb, encoderName, encodingToken, propertyName, encodingToken, BASE_INDENT);
                        break;

                    case BEGIN_SET:
                        generateBitSetProperty(
                            sb, true, ENCODER, propertyName, encodingToken, encodingToken, BASE_INDENT, typeName);
                        break;

                    case BEGIN_COMPOSITE:
                        generateCompositeProperty(
                            sb, true, ENCODER, propertyName, encodingToken, encodingToken, BASE_INDENT, typeName);
                        break;

                    default:
                        break;
                }

                out.append(sb);
                i += encodingToken.componentTokenCount();
            }

            out.append(generateCompositeEncoderDisplay(decoderName));
            out.append("}\n");
        }
    }

    private Set<String> scanPackagesToImport(final List<Token> tokens)
    {
        if (!shouldSupportTypesPackageNames)
        {
            return PACKAGES_EMPTY_SET;
        }

        final Set<String> packagesToImport = new HashSet<>();

        for (int i = 1, limit = tokens.size() - 1; i < limit; i++)
        {
            final Token typeToken = tokens.get(i);
            if (typeToken.signal() == Signal.BEGIN_ENUM ||
                typeToken.signal() == Signal.BEGIN_SET ||
                typeToken.signal() == Signal.BEGIN_COMPOSITE)
            {
                if (typeToken.packageName() != null)
                {
                    packagesToImport.add(typeToken.packageName());
                }
            }
        }

        return packagesToImport;
    }

    private void generateChoiceClear(final Appendable out, final String bitSetClassName, final Token token)
        throws IOException
    {
        final Encoding encoding = token.encoding();
        final String literalValue = generateLiteral(encoding.primitiveType(), "0");
        final String byteOrderStr = byteOrderString(encoding);

        final String clearStr = generatePut(encoding.primitiveType(), "offset", literalValue, byteOrderStr);
        out.append("\n")
            .append("    public clear(): ").append(bitSetClassName).append("\n")
            .append("    {\n")
            .append("        ").append(clearStr).append(";\n")
            .append("        return this;\n")
            .append("    }\n");
    }

    private void generateChoiceDecoders(final Appendable out, final List<Token> tokens)
        throws IOException
    {
        for (final Token token : tokens)
        {
            if (token.signal() == Signal.CHOICE)
            {
                final String choiceName = formatPropertyName(token.name());
                final Encoding encoding = token.encoding();
                final String choiceBitIndex = encoding.constValue().toString();
                final String byteOrderStr = byteOrderString(encoding);
                final PrimitiveType primitiveType = encoding.primitiveType();
                final String argType = bitsetArgType(primitiveType);

                generateOptionDecodeJavadoc(out, INDENT, token);
                final String choiceGet = generateChoiceGet(primitiveType, choiceBitIndex, byteOrderStr);
                final String staticChoiceGet = generateStaticChoiceGet(primitiveType, choiceBitIndex);
                out.append("\n")
                    .append("    public ").append(choiceName).append("(): boolean\n")
                    .append("    {\n")
                    .append("        return ").append(choiceGet).append(";\n")
                    .append("    }\n\n")
                    .append("    public static ").append(choiceName)
                    .append("(value: ").append(argType).append("): boolean\n")
                    .append("    {\n").append("        return ").append(staticChoiceGet).append(";\n")
                    .append("    }\n");
            }
        }
    }

    private void generateChoiceEncoders(final Appendable out, final String bitSetClassName, final List<Token> tokens)
        throws IOException
    {
        for (final Token token : tokens)
        {
            if (token.signal() == Signal.CHOICE)
            {
                final String choiceName = formatPropertyName(token.name());
                final Encoding encoding = token.encoding();
                final String choiceBitIndex = encoding.constValue().toString();
                final String byteOrderStr = byteOrderString(encoding);
                final PrimitiveType primitiveType = encoding.primitiveType();
                final String argType = bitsetArgType(primitiveType);

                generateOptionEncodeJavadoc(out, INDENT, token);
                final String choicePut = generateChoicePut(encoding.primitiveType(), choiceBitIndex, byteOrderStr);
                final String staticChoicePut = generateStaticChoicePut(encoding.primitiveType(), choiceBitIndex);
                out.append("\n")
                    .append("    public ").append(choiceName)
                    .append("(value: boolean): ").append(bitSetClassName).append("\n")
                    .append("    {\n")
                    .append(choicePut).append("\n")
                    .append("        return this;\n")
                    .append("    }\n\n")
                    .append("    public static ").append(choiceName)
                    .append("(bits: ").append(argType).append(", value: boolean): ").append(argType).append("\n")
                    .append("    {\n")
                    .append(staticChoicePut)
                    .append("    }\n");
            }
        }
    }

    private String bitsetArgType(final PrimitiveType primitiveType)
    {
        switch (primitiveType)
        {
            case UINT8:
                return "number";

            case UINT16:
                return "number";

            case UINT32:
                return "number";

            case UINT64:
                return "bigint";

            default:
                throw new IllegalStateException("Invalid type: " + primitiveType);
        }
    }

    private CharSequence generateEnumValues(final List<Token> tokens, final String nullVal)
    {
        final StringBuilder sb = new StringBuilder();

        for (final Token token : tokens)
        {
            final Encoding encoding = token.encoding();
            // final CharSequence constVal =
            //  generateLiteral(encoding.primitiveType(), encoding.constValue().toString());
            // generateTypeJavadoc(sb, INDENT, token);
            // sb.append(INDENT).append(token.name()).append(" = ").append(constVal).append(",\n\n");
            sb.append(INDENT).append(token.name()).append(" = ")
                .append(encoding.constValue().toString()).append(",\n\n");
        }

        if (shouldDecodeUnknownEnumValues)
        {
            sb.append(INDENT).append("/**\n");
            sb.append(INDENT).append(" * To be used to represent an unknown value from a later version.\n");
            sb.append(INDENT).append(" */\n");
            sb.append(INDENT).append("SBE_UNKNOWN").append(" = ").append(nullVal).append(",\n\n");
        }

        sb.append(INDENT).append("/**\n");
        sb.append(INDENT).append(" * To be used to represent not present or null.\n");
        sb.append(INDENT).append(" */\n");
        sb.append(INDENT).append("NULL_VAL").append(" = ").append(nullVal).append(",\n\n");

        return sb;
    }

    private CharSequence generateEnumBody(final Token token, final String enumName)
    {
        final String javaEncodingType = primitiveTypeName(token);

        //todo remove
        // return
        //     "    private value: " + javaEncodingType + ";\n\n" +
        //     "    " + enumName + "(value: " + javaEncodingType + ")\n" +
        //     "    {\n" +
        //     "        this.value = value;\n" +
        //     "    }\n\n" +
        //     "    /**\n" +
        //     "     * The raw encoded value in the Java type representation.\n" +
        //     "     *\n" +
        //     "     * @return the raw value encoded.\n" +
        //     "     */\n" +
        //     "    public value(): " + javaEncodingType + "\n" +
        //     "    {\n" +
        //     "        return value;\n" +
        //     "    }\n";

        return "";
    }

    private CharSequence generateEnumLookupMethod(final List<Token> tokens, final String enumName, final String nullVal)
    {
        // final StringBuilder sb = new StringBuilder();
        // final PrimitiveType primitiveType = tokens.get(0).encoding().primitiveType();

        // sb.append("\n")
        //     .append("    /**\n")
        //     .append("     * Lookup the enum value representing the value.\n")
        //     .append("     *\n")
        //     .append("     * @param value encoded to be looked up.\n")
        //     .append("     * @return the enum value representing the value.\n")
        //     .append("     */\n")
        //     .append("    public static ")
        //     .append(" get(value: ").append(typeScriptTypeName(primitiveType)).append("): ").append(enumName)
        //     .append("\n")
        //     .append("    {\n")
        //     .append("        switch (value)\n").append("        {\n");

        // for (final Token token : tokens)
        // {
        //     final String constStr = token.encoding().constValue().toString();
        //     final String name = token.name();
        //     sb.append("            case ").append(constStr).append(": return ").append(name).append(";\n");
        // }

        // sb.append("            case ").append(nullVal).append(": return NULL_VAL").append(";\n");

        // final String handleUnknownLogic = shouldDecodeUnknownEnumValues ?
        //     INDENT + INDENT + "return SBE_UNKNOWN;\n" :
        //     INDENT + INDENT + "throw new Error(\"Unknown value: \" + value);\n";

        // sb.append("        }\n\n")
        //     .append(handleUnknownLogic)
        //     .append("    }\n");

        // return sb;

        return "";
    }

    private StringBuilder generateImportStatements(final Set<String> packages, final String currentPackage)
    {
        final StringBuilder importStatements = new StringBuilder();

        // for (final String candidatePackage : packages)
        // {
        //     if (!candidatePackage.equals(currentPackage))
        //     {
        //         importStatements.append("import ").append(candidatePackage).append(".*;\n");
        //     }
        // }

        // if (importStatements.length() > 0)
        // {
        //     importStatements.append("\n\n");
        // }

        return importStatements;
    }

    private String interfaceImportLine()
    {
        if (!shouldGenerateInterfaces)
        {
            return "\n";
        }

        // return "import " + JAVA_INTERFACE_PACKAGE + ".*;\n\n";

        return "";
    }


    private CharSequence generateFileHeader(final String packageName, final Set<String> importedTypesPackages,
        final String fqBuffer)
    {
        final StringBuilder importStatements = generateImportStatements(importedTypesPackages, packageName);

        // return "/* Generated SBE (Simple Binary Encoding) message codec. */\n" +
        //     "package " + packageName + ";\n\n" +
        //     "import " + fqBuffer + ";\n" +
        //     interfaceImportLine() +
        //     importStatements;

        return "/* Generated SBE (Simple Binary Encoding) message codec. */\n";
    }

    private CharSequence generateMainHeader(
        final String packageName, final CodecType codecType, final boolean hasVarData)
    {
        final StringBuilder importStatements = generateImportStatements(packageNameByTypes, packageName);

        // if (fqMutableBuffer.equals(fqReadOnlyBuffer))
        // {
        //     return
        //         "/* Generated SBE (Simple Binary Encoding) message codec. */\n" +
        //         "package " + packageName + ";\n\n" +
        //         "import " + fqMutableBuffer + ";\n" +
        //         interfaceImportLine() +
        //         importStatements;
        // }
        // else
        // {
        //     final boolean hasMutableBuffer = ENCODER == codecType || hasVarData;
        //     final boolean hasReadOnlyBuffer = DECODER == codecType || hasVarData;

        //     return
        //         "/* Generated SBE (Simple Binary Encoding) message codec. */\n" +
        //         "package " + packageName + ";\n\n" +
        //         (hasMutableBuffer ? "import " + fqMutableBuffer + ";\n" : "") +
        //         (hasReadOnlyBuffer ? "import " + fqReadOnlyBuffer + ";\n" : "") +
        //         interfaceImportLine() +
        //         importStatements;
        // }

        return "/* Generated SBE (Simple Binary Encoding) message codec. */\n";
    }

    private static CharSequence generateEnumFileHeader(final String packageName)
    {
        //todo remove
        // return
        //     "/* Generated SBE (Simple Binary Encoding) message codec. */\n" +
        //     "package " + packageName + ";\n\n";
        return
            "/* Generated SBE (Simple Binary Encoding) message codec. */\n" +
            "\n\n";
    }

    private void generateAnnotations(
        final String indent,
        final String className,
        final List<Token> tokens,
        final Appendable out,
        final Function<String, String> nameMapping) throws IOException
    {
        final List<String> groupClassNames = new ArrayList<>();
        int level = 0;

        for (final Token token : tokens)
        {
            if (token.signal() == Signal.BEGIN_GROUP)
            {
                if (1 == ++level)
                {
                    groupClassNames.add(formatClassName(nameMapping.apply(token.name())));
                }
            }
            else if (token.signal() == Signal.END_GROUP)
            {
                --level;
            }
        }

//todo: check how to generate this in TS
        // if (!groupClassNames.isEmpty())
        // {
        //     out.append(indent).append("@uk.co.real_logic.sbe.codec.java.GroupOrder({\n");
        //     int i = 0;
        //     for (final String name : groupClassNames)
        //     {
        //         out.append(indent).append(INDENT).append(className).append('.').append(name).append(".class");
        //         if (++i < groupClassNames.size())
        //         {
        //             out.append(",\n");
        //         }
        //     }

        //     out.append("})");
        // }
    }

    private static CharSequence generateDeclaration(
        final String className, final String implementsString, final Token typeToken)
    {
        final StringBuilder sb = new StringBuilder();

        generateTypeJavadoc(sb, BASE_INDENT, typeToken);
        if (typeToken.deprecated() > 0)
        {
            sb.append("@deprecated\n");
        }
        sb
            // .append("@SuppressWarnings(\"all\")\n") //todo: is this supported in TS?
            .append("class ").append(className).append(implementsString).append('\n')
            .append("{\n");

        return sb;
    }

    private void generatePackageInfo() throws IOException
    {
        try (Writer out = outputManager.createOutput(PACKAGE_INFO))
        {
            out.append(
                "/* Generated SBE (Simple Binary Encoding) message codecs.*/\n" +
                "/**\n" +
                " * ").append(ir.description()).append("\n")
                .append(
                " */\n" +
                "package ").append(ir.applicableNamespace()).append(";\n");
        }
    }

    private void generateMetaAttributeEnum() throws IOException
    {
        try (Writer out = outputManager.createOutput(META_ATTRIBUTE_ENUM))
        {
            out.append(
                "/* Generated SBE (Simple Binary Encoding) message codec. */\n" +
                "package ").append(ir.applicableNamespace()).append(";\n\n")
                .append(
                "/**\n" +
                " * Meta attribute enum for selecting a particular meta attribute value.\n" +
                " */\n" +
                // " @SuppressWarnings(\"all\")\n" +
                "enum MetaAttribute\n" +
                "{\n" +
                "    /**\n" +
                "     * The epoch or start of time. Default is 'UNIX' which is midnight 1st January 1970 UTC.\n" +
                "     */\n" +
                "    EPOCH,\n\n" +
                "    /**\n" +
                "     * Time unit applied to the epoch. Can be second, millisecond, microsecond, or nanosecond.\n" +
                "     */\n" +
                "    TIME_UNIT,\n\n" +
                "    /**\n" +
                "     * The type relationship to a FIX tag value encoded type. For reference only.\n" +
                "     */\n" +
                "    SEMANTIC_TYPE,\n\n" +
                "    /**\n" +
                "     * Field presence indication. Can be optional, required, or constant.\n" +
                "     */\n" +
                "    PRESENCE\n" +
                "}\n");
        }
    }

    private static CharSequence generateEnumDeclaration(final String name, final Token typeToken)
    {
        final StringBuilder sb = new StringBuilder();

        sb.append("enum ").append(name).append("\n{\n");

        return sb;
    }

    private void generatePrimitiveDecoder(
        final StringBuilder sb,
        final boolean inComposite,
        final String propertyName,
        final Token propertyToken,
        final Token encodingToken,
        final String indent)
    {
        final String formattedPropertyName = formatPropertyName(propertyName);

        generatePrimitiveFieldMetaMethod(sb, formattedPropertyName, encodingToken, indent);

        if (encodingToken.isConstantEncoding())
        {
            generateConstPropertyMethods(sb, formattedPropertyName, encodingToken, indent);
        }
        else
        {
            sb.append(generatePrimitivePropertyDecodeMethods(
                inComposite, formattedPropertyName, propertyToken, encodingToken, indent));
        }
    }

    private void generatePrimitiveEncoder(
        final StringBuilder sb,
        final String containingClassName,
        final String propertyName,
        final Token token,
        final String indent)
    {
        final String formattedPropertyName = formatPropertyName(propertyName);

        generatePrimitiveFieldMetaMethod(sb, formattedPropertyName, token, indent);

        if (!token.isConstantEncoding())
        {
            sb.append(generatePrimitivePropertyEncodeMethods(
                containingClassName, formattedPropertyName, token, indent));
        }
        else
        {
            generateConstPropertyMethods(sb, formattedPropertyName, token, indent);
        }
    }

    private CharSequence generatePrimitivePropertyDecodeMethods(
        final boolean inComposite,
        final String propertyName,
        final Token propertyToken,
        final Token encodingToken,
        final String indent)
    {
        return encodingToken.matchOnLength(
            () -> generatePrimitivePropertyDecode(inComposite, propertyName, propertyToken, encodingToken, indent),
            () -> generatePrimitiveArrayPropertyDecode(
                inComposite, propertyName, propertyToken, encodingToken, indent));
    }

    private CharSequence generatePrimitivePropertyEncodeMethods(
        final String containingClassName, final String propertyName, final Token token, final String indent)
    {
        return token.matchOnLength(
            () -> generatePrimitivePropertyEncode(containingClassName, propertyName, token, indent),
            () -> generatePrimitiveArrayPropertyEncode(containingClassName, propertyName, token, indent));
    }

    private void generatePrimitiveFieldMetaMethod(
        final StringBuilder sb, final String propertyName, final Token token, final String indent)
    {
        final PrimitiveType primitiveType = token.encoding().primitiveType();
        final String javaTypeName = typeScriptTypeName(primitiveType);
        final String formattedPropertyName = formatPropertyName(propertyName);

        final String nullValue = generateLiteral(primitiveType, token.encoding().applicableNullValue().toString());
        generatePrimitiveFieldMetaMethod(sb, indent, javaTypeName, formattedPropertyName, "Null", nullValue);

        final String minValue = generateLiteral(primitiveType, token.encoding().applicableMinValue().toString());
        generatePrimitiveFieldMetaMethod(sb, indent, javaTypeName, formattedPropertyName, "Min", minValue);

        final String maxValue = generateLiteral(primitiveType, token.encoding().applicableMaxValue().toString());
        generatePrimitiveFieldMetaMethod(sb, indent, javaTypeName, formattedPropertyName, "Max", maxValue);
    }

    private void generatePrimitiveFieldMetaMethod(
        final StringBuilder sb,
        final String indent,
        final String javaTypeName,
        final String formattedPropertyName,
        final String metaType,
        final String retValue)
    {
        sb.append("\n")
            .append(indent).append("    public static ")
            .append(" ").append(formattedPropertyName).append(metaType).append("Value(): ").append(javaTypeName)
            .append("\n")
            .append(indent).append("    {\n")
            .append(indent).append("        return ").append(retValue).append(";\n")
            .append(indent).append("    }\n");
    }

    private CharSequence generatePrimitivePropertyDecode(
        final boolean inComposite,
        final String propertyName,
        final Token propertyToken,
        final Token encodingToken,
        final String indent)
    {
        final Encoding encoding = encodingToken.encoding();
        final String javaTypeName = typeScriptTypeName(encoding.primitiveType());

        final int offset = encodingToken.offset();
        final String byteOrderStr = byteOrderString(encoding);

        return String.format(
            "\n" +
            indent + "    public %s(): %s\n" +
            indent + "    {\n" +
            "%s" +
            indent + "        return %s;\n" +
            indent + "    }\n\n",
            formatPropertyName(propertyName),
            javaTypeName,
            generateFieldNotPresentCondition(inComposite, propertyToken.version(), encoding, indent),
            generateGet(encoding.primitiveType(), "this._offset + " + offset, byteOrderStr));
    }

    private CharSequence generatePrimitivePropertyEncode(
        final String containingClassName, final String propertyName, final Token token, final String indent)
    {
        final Encoding encoding = token.encoding();
        final String javaTypeName = typeScriptTypeName(encoding.primitiveType());
        final int offset = token.offset();
        final String byteOrderStr = byteOrderString(encoding);

        return String.format(
            "\n" +
            indent + "    public %s(value: %s): %s\n" +
            indent + "    {\n" +
            indent + "        %s;\n" +
            indent + "        return this;\n" +
            indent + "    }\n\n",
            formatPropertyName(propertyName),
            javaTypeName,
            formatClassName(containingClassName),
            generatePut(encoding.primitiveType(), "this._offset + " + offset, "value", byteOrderStr));
    }

    private CharSequence generateWrapFieldNotPresentCondition(final int sinceVersion, final String indent)
    {
        if (0 == sinceVersion)
        {
            return "";
        }

        return
            indent + "        if (this._parentMessage._actingVersion < " + sinceVersion + ")\n" +
            indent + "        {\n" +
            indent + "            this.wrapBuffer.wrap(this._buffer, this._offset, 0);\n" +
            indent + "            return;\n" +
            indent + "        }\n\n";
    }

    private CharSequence generateFieldNotPresentCondition(
        final boolean inComposite, final int sinceVersion, final Encoding encoding, final String indent)
    {
        if (inComposite || 0 == sinceVersion)
        {
            return "";
        }

        final String nullValue = generateLiteral(encoding.primitiveType(), encoding.applicableNullValue().toString());
        return
            indent + "        if (this._parentMessage._actingVersion < " + sinceVersion + ")\n" +
            indent + "        {\n" +
            indent + "            return " + nullValue + ";\n" +
            indent + "        }\n\n";
    }

    private static CharSequence generateArrayFieldNotPresentCondition(final int sinceVersion, final String indent)
    {
        if (0 == sinceVersion)
        {
            return "";
        }

        return
            indent + "        if (this._parentMessage.actingVersion < " + sinceVersion + ")\n" +
            indent + "        {\n" +
            indent + "            return 0;\n" +
            indent + "        }\n\n";
    }

    private static CharSequence generateStringNotPresentConditionForAppendable(
        final int sinceVersion, final String indent)
    {
        if (0 == sinceVersion)
        {
            return "";
        }

        return
            indent + "        if (this._parentMessage.actingVersion < " + sinceVersion + ")\n" +
            indent + "        {\n" +
            indent + "            return 0;\n" +
            indent + "        }\n\n";
    }

    private static CharSequence generateStringNotPresentCondition(final int sinceVersion, final String indent)
    {
        if (0 == sinceVersion)
        {
            return "";
        }

        return
            indent + "        if (this._parentMessage.actingVersion < " + sinceVersion + ")\n" +
            indent + "        {\n" +
            indent + "            return \"\";\n" +
            indent + "        }\n\n";
    }

    private static CharSequence generatePropertyNotPresentCondition(
        final boolean inComposite,
        final CodecType codecType,
        final Token propertyToken,
        final String enumName,
        final String indent)
    {
        if (inComposite || codecType == ENCODER || 0 == propertyToken.version())
        {
            return "";
        }

        final String nullValue = enumName == null ? "null" : (enumName + ".NULL_VAL");
        return
            indent + "        if (this._parentMessage.actingVersion < " + propertyToken.version() + ")\n" +
            indent + "        {\n" +
            indent + "            return " + nullValue + ";\n" +
            indent + "        }\n\n";
    }

    private CharSequence generatePrimitiveArrayPropertyDecode(
        final boolean inComposite,
        final String propertyName,
        final Token propertyToken,
        final Token encodingToken,
        final String indent)
    {
        final Encoding encoding = encodingToken.encoding();
        final String javaTypeName = typeScriptTypeName(encoding.primitiveType());
        final int offset = encodingToken.offset();
        final String byteOrderStr = byteOrderString(encoding);
        final int fieldLength = encodingToken.arrayLength();
        final int typeSize = sizeOfPrimitive(encoding);

        final StringBuilder sb = new StringBuilder();

        generateArrayLengthMethod(propertyName, indent, fieldLength, sb);

        new Formatter(sb).format("\n" +
            indent + "    public %s(index: number): %s\n" +
            indent + "    {\n" +
            indent + "        if (index < 0 || index >= %d)\n" +
            indent + "        {\n" +
            indent + "            throw new Error(\"index out of range: index=\" + index);\n" +
            indent + "        }\n\n" +
            "%s" +
            indent + "        let pos: number = this._offset + %d + (index * %d);\n\n" +
            indent + "        return %s;\n" +
            indent + "    }\n\n",
            propertyName,
            javaTypeName,
            fieldLength,
            generateFieldNotPresentCondition(inComposite, propertyToken.version(), encoding, indent),
            offset,
            typeSize,
            generateGet(encoding.primitiveType(), "pos", byteOrderStr));

        if (encoding.primitiveType() == PrimitiveType.CHAR)
        {
            generateCharacterEncodingMethod(sb, propertyName, encoding.characterEncoding(), indent);

            new Formatter(sb).format("\n" +
                indent + "    public get%s(dst: number[], dstOffset: number): number\n" +
                indent + "    {\n" +
                indent + "        let length: number = %d;\n" +
                indent + "        if (dstOffset < 0 || dstOffset > (dst.length - length))\n" +
                indent + "        {\n" +
                indent + "            throw new Error(" +
                "\"Copy will go out of range: offset=\" + dstOffset);\n" +
                indent + "        }\n\n" +
                "%s" +
                indent + "        this._buffer.getBytes(this._offset + %d, dst, dstOffset, length);\n\n" +
                indent + "        return length;\n" +
                indent + "    }\n",
                Generators.toUpperFirstChar(propertyName),
                fieldLength,
                generateArrayFieldNotPresentCondition(propertyToken.version(), indent),
                offset);

            new Formatter(sb).format("\n" +
                indent + "    public %s(): String\n" +
                indent + "    {\n" +
                "%s" +
                indent + "        let dst: number[] = new number[%d];\n" +
                indent + "        this._buffer.getBytes(this._offset + %d, dst, 0, %d);\n\n" +
                indent + "        end: number = 0;\n" +
                indent + "        for (; end < %d && dst[end] != 0; ++end);\n\n" +
                indent + "        return new String(dst, 0, end, %s);\n" +
                indent + "    }\n\n",
                propertyName,
                generateStringNotPresentCondition(propertyToken.version(), indent),
                fieldLength,
                offset,
                fieldLength,
                fieldLength,
                charset(encoding.characterEncoding()));

            if (isAsciiEncoding(encoding.characterEncoding()))
            {
                new Formatter(sb).format("\n" +
                    indent + "    public get%1$s(value: Appendable): number\n" +
                    indent + "    {\n" +
                    "%2$s" +
                    indent + "        for (let i = 0; i < %3$d; ++i)\n" +
                    indent + "        {\n" +
                    indent + "            let c: number = this._buffer.getByte(this._offset + %4$d + i) & 0xFF;\n" +
                    indent + "            if (c == 0)\n" +
                    indent + "            {\n" +
                    indent + "                return i;\n" +
                    indent + "            }\n\n" +
                    indent + "            try\n" +
                    indent + "            {\n" +
                    indent + "                value.append(c > 127 ? '?' : (char)c);\n" +
                    indent + "            }\n" +
                    indent + "            catch (Error ex)\n" + //todo: check if this is the right exception
                    indent + "            {\n" +
                    indent + "                throw new Error(ex);\n" +
                    indent + "            }\n" +
                    indent + "        }\n\n" +
                    indent + "        return %3$d;\n" +
                    indent + "    }\n\n",
                    Generators.toUpperFirstChar(propertyName),
                    generateStringNotPresentConditionForAppendable(propertyToken.version(), indent),
                    fieldLength,
                    offset);
            }
        }
        else if (encoding.primitiveType() == PrimitiveType.UINT8)
        {
            new Formatter(sb).format("\n" +
                indent + "    public get%s(dst: number[], dstOffset: number, length: number): number\n" +
                indent + "    {\n" +
                "%s" +
                indent + "        let bytesCopied: number = Math.min(length, %d);\n" +
                indent + "        this._buffer.getBytes(this._offset + %d, dst, dstOffset, bytesCopied);\n\n" +
                indent + "        return bytesCopied;\n" +
                indent + "    }\n",
                Generators.toUpperFirstChar(propertyName),
                generateArrayFieldNotPresentCondition(propertyToken.version(), indent),
                fieldLength,
                offset);

            new Formatter(sb).format("\n" +
                indent + "    public get%s(dst: %s, dstOffset: number, length: number): number\n" +
                indent + "    {\n" +
                "%s" +
                indent + "        let bytesCopied: number = Math.min(length, %d);\n" +
                indent + "        this._buffer.getBytes(this._offset + %d, dst, dstOffset, bytesCopied);\n\n" +
                indent + "        return bytesCopied;\n" +
                indent + "    }\n",
                Generators.toUpperFirstChar(propertyName),
                fqMutableBuffer,
                generateArrayFieldNotPresentCondition(propertyToken.version(), indent),
                fieldLength,
                offset);

            new Formatter(sb).format("\n" +
                indent + "    public wrap%s(wrapBuffer: %s)\n" +
                indent + "    {\n" +
                "%s" +
                indent + "        wrapBuffer.wrap(this._buffer, this._offset + %d, %d);\n" +
                indent + "    }\n",
                Generators.toUpperFirstChar(propertyName),
                readOnlyBuffer,
                generateWrapFieldNotPresentCondition(propertyToken.version(), indent),
                offset,
                fieldLength);
        }

        return sb;
    }

    private static void generateArrayLengthMethod(
        final String propertyName, final String indent, final int fieldLength, final StringBuilder sb)
    {
        final String formatPropertyName = formatPropertyName(propertyName);
        sb.append("\n")
            .append(indent).append("    public static ").append(formatPropertyName).append("Length(): number\n")
            .append(indent).append("    {\n")
            .append(indent).append("        return ").append(fieldLength).append(";\n")
            .append(indent).append("    }\n\n");
    }

    private String byteOrderString(final Encoding encoding)
    {
        //todo: convert to TS
        return sizeOfPrimitive(encoding) == 1 ? "" : ", ByteOrder." + encoding.byteOrder();
    }

    private CharSequence generatePrimitiveArrayPropertyEncode(
        final String containingClassName, final String propertyName, final Token token, final String indent)
    {
        final Encoding encoding = token.encoding();
        final PrimitiveType primitiveType = encoding.primitiveType();
        final String javaTypeName = typeScriptTypeName(primitiveType);
        final int offset = token.offset();
        final String byteOrderStr = byteOrderString(encoding);
        final int arrayLength = token.arrayLength();
        final int typeSize = sizeOfPrimitive(encoding);

        final StringBuilder sb = new StringBuilder();
        final String className = formatClassName(containingClassName);

        generateArrayLengthMethod(propertyName, indent, arrayLength, sb);

        new Formatter(sb).format("\n" +
            indent + "    public %s(index: number, value: %s): %s\n" +
            indent + "    {\n" +
            indent + "        if (index < 0 || index >= %d)\n" +
            indent + "        {\n" +
            indent + "            throw new Error(\"index out of range: index=\" + index);\n" +
            indent + "        }\n\n" +
            indent + "        let pos: number = this._offset + %d + (index * %d);\n" +
            indent + "        %s;\n\n" +
            indent + "        return this;\n" +
            indent + "    }\n",
            propertyName,
            className,
            javaTypeName,
            arrayLength,
            offset,
            typeSize,
            generatePut(primitiveType, "pos", "value", byteOrderStr));

        if (arrayLength > 1 && arrayLength <= 4)
        {
            sb.append(indent)
                .append("    public")
                .append(" put").append(Generators.toUpperFirstChar(propertyName))
                .append("(value0: ").append(javaTypeName);

            for (int i = 1; i < arrayLength; i++)
            {
                sb.append(", value").append(i).append(": ").append(javaTypeName);
            }

            sb.append("): ").append(className).append("\n");
            sb.append(indent).append("    {\n");

            for (int i = 0; i < arrayLength; i++)
            {
                final String indexStr = "this._offset + " + (offset + (typeSize * i));

                sb.append(indent).append("        ")
                    .append(generatePut(primitiveType, indexStr, "value" + i, byteOrderStr))
                    .append(";\n");
            }

            sb.append("\n");
            sb.append(indent).append("        return this;\n");
            sb.append(indent).append("    }\n");
        }

        if (primitiveType == PrimitiveType.CHAR)
        {
            generateCharArrayEncodeMethods(
                containingClassName, propertyName, indent, encoding, offset, arrayLength, sb);
        }
        else if (primitiveType == PrimitiveType.UINT8)
        {
            generateByteArrayEncodeMethods(
                containingClassName, propertyName, indent, offset, arrayLength, sb);
        }

        return sb;
    }

    private void generateCharArrayEncodeMethods(
        final String containingClassName,
        final String propertyName,
        final String indent,
        final Encoding encoding,
        final int offset,
        final int fieldLength,
        final StringBuilder sb)
    {
        generateCharacterEncodingMethod(sb, propertyName, encoding.characterEncoding(), indent);

        new Formatter(sb).format("\n" +
            indent + "    public put%s(src: number[], srcOffset: number): %s\n" +
            indent + "    {\n" +
            indent + "        length: number = %d;\n" +
            indent + "        if (srcOffset < 0 || srcOffset > (src.length - length))\n" +
            indent + "        {\n" +
            indent + "            throw new Error(" +
            "\"Copy will go out of range: offset=\" + srcOffset);\n" +
            indent + "        }\n\n" +
            indent + "        this._buffer.putBytes(this._offset + %d, src, srcOffset, length);\n\n" +
            indent + "        return this;\n" +
            indent + "    }\n",
            formatClassName(containingClassName),
            Generators.toUpperFirstChar(propertyName),
            fieldLength,
            offset);

        if (isAsciiEncoding(encoding.characterEncoding()))
        {
            new Formatter(sb).format("\n" +
                indent + "    public %2$s(src: String): %1$s\n" +
                indent + "    {\n" +
                indent + "        let length: number = %3$d;\n" +
                indent + "        let srcLength: number = null == src ? 0 : src.length();\n" +
                indent + "        if (srcLength > length)\n" +
                indent + "        {\n" +
                indent + "            throw new Error(" +
                "\"String too large for copy: byte length=\" + srcLength);\n" +
                indent + "        }\n\n" +
                indent + "        this._buffer.putStringWithoutLengthAscii(this._offset + %4$d, src);\n\n" +
                indent + "        for (let start = srcLength; start < length; ++start)\n" +
                indent + "        {\n" +
                indent + "            this._buffer.putByte(this._offset + %4$d + start, (byte)0);\n" +
                indent + "        }\n\n" +
                indent + "        return this;\n" +
                indent + "    }\n",
                formatClassName(containingClassName),
                propertyName,
                fieldLength,
                offset);

            new Formatter(sb).format("\n" +
                indent + "    public %2$s(src: CharSequence): %1$s\n" +
                //todo: check if this is the right exception in TS
                indent + "    {\n" +
                indent + "        let length: number = %3$d;\n" +
                indent + "        let srcLength: number = null == src ? 0 : src.length();\n" +
                indent + "        if (srcLength > length)\n" +
                indent + "        {\n" +
                indent + "            throw new Error(" +
                "\"CharSequence too large for copy: byte length=\" + srcLength);\n" +
                indent + "        }\n\n" +
                indent + "        this._buffer.putStringWithoutLengthAscii(this._offset + %4$d, src);\n\n" +
                indent + "        for (let start = srcLength; start < length; ++start)\n" +
                indent + "        {\n" +
                indent + "            this._buffer.putByte(this._offset + %4$d + start, (byte)0);\n" +
                indent + "        }\n\n" +
                indent + "        return this;\n" +
                indent + "    }\n",
                formatClassName(containingClassName),
                propertyName,
                fieldLength,
                offset);
        }
        else
        {
            new Formatter(sb).format("\n" +
                indent + "    public %s(src: String): %s\n" +
                indent + "    {\n" +
                indent + "        let length: number = %d;\n" +
                indent + "        let bytes: number[] = (null == src || src.isEmpty()) ?" +
                " org.agrona.collections.ArrayUtil.EMPTY_BYTE_ARRAY : src.getBytes(%s);\n" +
                indent + "        if (bytes.length > length)\n" +
                indent + "        {\n" +
                indent + "            throw new Error(" +
                "\"String too large for copy: byte length=\" + bytes.length);\n" +
                indent + "        }\n\n" +
                indent + "        this._buffer.putBytes(this._offset + %d, bytes, 0, bytes.length);\n\n" +
                indent + "        for (let start = bytes.length; start < length; ++start)\n" +
                indent + "        {\n" +
                indent + "            this._buffer.putByte(this._offset + %d + start, (byte)0);\n" +
                indent + "        }\n\n" +
                indent + "        return this;\n" +
                indent + "    }\n",
                formatClassName(containingClassName),
                propertyName,
                fieldLength,
                charset(encoding.characterEncoding()),
                offset,
                offset);
        }
    }

    private void generateByteArrayEncodeMethods(
        final String containingClassName,
        final String propertyName,
        final String indent,
        final int offset,
        final int fieldLength,
        final StringBuilder sb)
    {
        new Formatter(sb).format("\n" +
            indent + "    public put%s(src: number[], srcOffset: number, length: number): %s\n" +
            indent + "    {\n" +
            indent + "        if (length > %d)\n" +
            indent + "        {\n" +
            indent + "            throw new Error(" +
            "\"length > maxValue for type: \" + length);\n" +
            indent + "        }\n\n" +
            indent + "        this._buffer.putBytes(this._offset + %d, src, srcOffset, length);\n" +
            indent + "        for (let i = length; i < %d; i++)\n" +
            indent + "        {\n" +
            indent + "            this._buffer.putByte(this._offset + %d + i, (byte)0);\n" +
            indent + "        }\n\n" +
            indent + "        return this;\n" +
            indent + "    }\n",
            formatClassName(containingClassName),
            Generators.toUpperFirstChar(propertyName),
            fieldLength,
            offset,
            fieldLength,
            offset);

        new Formatter(sb).format("\n" +
            indent + "    public %s put%s(final %s src, final int srcOffset, final int length)\n" +
            indent + "    {\n" +
            indent + "        if (length > %d)\n" +
            indent + "        {\n" +
            indent + "            throw new Error(" +
            "\"length > maxValue for type: \" + length);\n" +
            indent + "        }\n\n" +
            indent + "        this._buffer.putBytes(this._offset + %d, src, srcOffset, length);\n" +
            indent + "        for (let i = length; i < %d; i++)\n" +
            indent + "        {\n" +
            indent + "            this._buffer.putByte(this._offset + %d + i, (byte)0);\n" +
            indent + "        }\n\n" +
            indent + "        return this;\n" +
            indent + "    }\n",
            formatClassName(containingClassName),
            Generators.toUpperFirstChar(propertyName),
            fqReadOnlyBuffer,
            fieldLength,
            offset,
            fieldLength,
            offset);
    }

    private static int sizeOfPrimitive(final Encoding encoding)
    {
        return encoding.primitiveType().size();
    }

    private static void generateCharacterEncodingMethod(
        final StringBuilder sb, final String propertyName, final String characterEncoding, final String indent)
    {
        if (null != characterEncoding)
        {
            final String propName = formatPropertyName(propertyName);
            sb.append("\n")
                .append(indent).append("    public static ").append(propName).append("CharacterEncoding(): string\n")
                .append(indent).append("    {\n")
                .append(indent).append("        return ").append(charsetName(characterEncoding)).append(";\n")
                .append(indent).append("    }\n");
        }
    }

    private void generateConstPropertyMethods(
        final StringBuilder sb, final String propertyName, final Token token, final String indent)
    {
        final String formattedPropertyName = formatPropertyName(propertyName);
        final Encoding encoding = token.encoding();
        if (encoding.primitiveType() != PrimitiveType.CHAR)
        {
            new Formatter(sb).format("\n" +
                indent + "    public %s(): %s\n" +
                indent + "    {\n" +
                indent + "        return _%s;\n" +
                indent + "    }\n",
                typeScriptTypeName(encoding.primitiveType()),
                formattedPropertyName,
                generateLiteral(encoding.primitiveType(), encoding.constValue().toString()));

            return;
        }

        final String javaTypeName = typeScriptTypeName(encoding.primitiveType());
        final byte[] constBytes = encoding.constValue().byteArrayValue(encoding.primitiveType());
        final CharSequence values = generateByteLiteralList(
            encoding.constValue().byteArrayValue(encoding.primitiveType()));

        new Formatter(sb).format("\n" +
            "\n" +
            indent + "    private static _%s_VALUE: number[] = { _%s };\n",
            propertyName.toUpperCase(),
            values);

        generateArrayLengthMethod(formattedPropertyName, indent, constBytes.length, sb);

        new Formatter(sb).format("\n" +
            indent + "    public %s(index: number): %s\n" +
            indent + "    {\n" +
            indent + "        return %s_VALUE[index];\n" +
            indent + "    }\n\n",
            javaTypeName,
            formattedPropertyName,
            propertyName.toUpperCase());

        sb.append(String.format(
            indent + "    public get%s(dst: number[], offset: number, length: number): number\n" +
            indent + "    {\n" +
            indent + "        bytesCopied: number = Math.min(length, %d);\n" +
            indent + "        System.arraycopy(%s_VALUE, 0, dst, offset, bytesCopied);\n\n" + //todo convert to TS
            indent + "        return bytesCopied;\n" +
            indent + "    }\n",
            Generators.toUpperFirstChar(propertyName),
            constBytes.length,
            propertyName.toUpperCase()));

        if (constBytes.length > 1)
        {
            new Formatter(sb).format("\n" +
                indent + "    public %s(): string\n" +
                indent + "    {\n" +
                indent + "        return \"_%s\";\n" +
                indent + "    }\n\n",
                formattedPropertyName,
                encoding.constValue());
        }
        else
        {
            new Formatter(sb).format("\n" +
                indent + "    public %s(): number\n" +
                indent + "    {\n" +
                indent + "        return (_%s as number);\n" +
                indent + "    }\n\n",
                formattedPropertyName,
                encoding.constValue());
        }
    }

    private static CharSequence generateByteLiteralList(final byte[] bytes)
    {
        final StringBuilder values = new StringBuilder();
        for (final byte b : bytes)
        {
            values.append(b).append(", ");
        }

        if (values.length() > 0)
        {
            values.setLength(values.length() - 2);
        }

        return values;
    }

    private CharSequence generateFixedFlyweightCode(
        final String className, final int size, final String bufferImplementation)
    {
        final String schemaIdType = typeScriptTypeName(ir.headerStructure().schemaIdType());
        final String schemaIdAccessorType = shouldGenerateInterfaces ? "int" : schemaIdType;
        final String schemaVersionType = typeScriptTypeName(ir.headerStructure().schemaVersionType());
        final String schemaVersionAccessorType = shouldGenerateInterfaces ? "int" : schemaVersionType;
        final String semanticVersion = ir.semanticVersion() == null ? "" : ir.semanticVersion();

        return String.format(
            "    public static SCHEMA_ID: %5$s = %6$s;\n" +
            "    public static SCHEMA_VERSION: %7$s = %8$s;\n" +
            "    public static SEMANTIC_VERSION: string = \"%11$s\";\n" +
            "    public static ENCODED_LENGTH: number = %2$d;\n" +
            "    public static BYTE_ORDER: ByteOrder = ByteOrder.%4$s;\n\n" +
            "    private _offset: number;\n" +
            "    private _buffer: %3$s;\n\n" +
            "    public wrap(buffer: %3$s, offset: number): %1$s\n" +
            "    {\n" +
            "        if (buffer != this._buffer)\n" +
            "        {\n" +
            "            this._buffer = buffer;\n" +
            "        }\n" +
            "        this._offset = offset;\n\n" +
            "        return this;\n" +
            "    }\n\n" +
            "    public buffer(): %3$s\n" +
            "    {\n" +
            "        return this._buffer;\n" +
            "    }\n\n" +
            "    public offset(): number\n" +
            "    {\n" +
            "        return this._offset;\n" +
            "    }\n\n" +
            "    public encodedLength(): number\n" +
            "    {\n" +
            "        return " + className + ".ENCODED_LENGTH;\n" +
            "    }\n\n" +
            "    public sbeSchemaId(): %9$s\n" +
            "    {\n" +
            "        return " + className + ".SCHEMA_ID;\n" +
            "    }\n\n" +
            "    public sbeSchemaVersion(): %10$s\n" +
            "    {\n" +
            "        return " + className + ".SCHEMA_VERSION;\n" +
            "    }\n",
            className,
            size,
            bufferImplementation,
            ir.byteOrder(),
            schemaIdType,
            generateLiteral(ir.headerStructure().schemaIdType(), Integer.toString(ir.id())),
            schemaVersionType,
            generateLiteral(ir.headerStructure().schemaVersionType(), Integer.toString(ir.version())),
            schemaIdAccessorType,
            schemaVersionAccessorType,
            semanticVersion);
    }

    private CharSequence generateDecoderFlyweightCode(final String className, final Token token)
    {
        final String headerClassName = formatClassName(ir.headerStructure().tokens().get(0).applicableTypeName());

        final String methods =
            "    public wrap(\n" +
            "        buffer: " + readOnlyBuffer + ",\n" +
            "        offset: number,\n" +
            "        actingBlockLength: number,\n" +
            "        actingVersion: number): " + className + "\n" +
            "    {\n" +
            "        if (buffer != this._buffer)\n" +
            "        {\n" +
            "            this._buffer = buffer;\n" +
            "        }\n" +
            "        this._initialOffset = offset;\n" +
            "        this._offset = offset;\n" +
            "        this._actingBlockLength = actingBlockLength;\n" +
            "        this._actingVersion = actingVersion;\n" +
            "        this.setLimit(offset + actingBlockLength);\n\n" +
            "        return this;\n" +
            "    }\n\n" +

            "    public wrapAndApplyHeader(\n" +
            "        buffer: " + readOnlyBuffer + ",\n" +
            "        offset: number,\n" +
            "        headerDecoder: " + headerClassName + "Decoder): " + className + "\n" +
            "    {\n" +
            "        headerDecoder.wrap(buffer, offset);\n\n" +
            "        let templateId: number = headerDecoder.templateId();\n" +
            "        if (" + className + ".TEMPLATE_ID != templateId)\n" +
            "        {\n" +
            "            throw new Error(\"Invalid TEMPLATE_ID: \" + templateId);\n" +
            "        }\n\n" +
            "        return this.wrap(\n" +
            "            buffer,\n" +
            "            offset + " + headerClassName + "Decoder.ENCODED_LENGTH,\n" +
            "            headerDecoder.blockLength(),\n" +
            "            headerDecoder.version());\n" +
            "    }\n\n" +

            "    public sbeRewind(): " + className + "\n" +
            "    {\n" +
            "        return this.wrap(this._buffer, this._initialOffset," +
            " this._actingBlockLength, this._actingVersion);\n" +
            "    }\n\n" +

            "    public sbeDecodedLength(): number\n" +
            "    {\n" +
            "        let currentLimit: number = this.getLimit();\n" +
            "        this.sbeSkip();\n" +
            "        let decodedLength: number = this.encodedLength();\n" +
            "        this.setLimit(currentLimit);\n\n" +
            "        return decodedLength;\n" +
            "    }\n\n" +

            "    public actingVersion(): number\n" +
            "    {\n" +
            "        return this._actingVersion;\n" +
            "    }\n\n";

        return generateFlyweightCode(DECODER, className, token, methods, readOnlyBuffer);
    }

    private CharSequence generateFlyweightCode(
        final CodecType codecType,
        final String className,
        final Token token,
        final String wrapMethod,
        final String bufferImplementation)
    {
        final HeaderStructure headerStructure = ir.headerStructure();
        final String blockLengthType = typeScriptTypeName(headerStructure.blockLengthType());
        final String blockLengthAccessorType = shouldGenerateInterfaces ? "int" : blockLengthType;
        final String templateIdType = typeScriptTypeName(headerStructure.templateIdType());
        final String templateIdAccessorType = shouldGenerateInterfaces ? "int" : templateIdType;
        final String schemaIdType = typeScriptTypeName(headerStructure.schemaIdType());
        final String schemaIdAccessorType = shouldGenerateInterfaces ? "int" : schemaIdType;
        final String schemaVersionType = typeScriptTypeName(headerStructure.schemaVersionType());
        final String schemaVersionAccessorType = shouldGenerateInterfaces ? "int" : schemaVersionType;
        final String semanticType = token.encoding().semanticType() == null ? "" : token.encoding().semanticType();
        final String semanticVersion = ir.semanticVersion() == null ? "" : ir.semanticVersion();
        final String actingFields = codecType == CodecType.ENCODER ?
            "" :
            "    private _actingBlockLength: number = 0;\n" +
            "    private _actingVersion: number = 0;\n";

        return String.format(
            "    public static BLOCK_LENGTH: %1$s = %2$s;\n" +
            "    public static TEMPLATE_ID: %3$s = %4$s;\n" +
            "    public static SCHEMA_ID: %5$s = %6$s;\n" +
            "    public static SCHEMA_VERSION: %7$s = %8$s;\n" +
            "    public static SEMANTIC_VERSION: string = \"%19$s\";\n" +
            "    public static BYTE_ORDER: ByteOrder = ByteOrder.%14$s;\n\n" + //todo: convert to TS
            "    private _parentMessage: %9$s = this;\n" +
            "    private _buffer: %11$s;\n" +
            "    private _initialOffset: number = 0;\n" +
            "    private _offset: number = 0;\n" +
            "    private _limit: number = 0;\n" +
            "%13$s" +
            "\n" +
            "    public sbeBlockLength(): %15$s\n" +
            "    {\n" +
            "        return " + className + ".BLOCK_LENGTH;\n" +
            "    }\n\n" +
            "    public sbeTemplateId(): %16$s\n" +
            "    {\n" +
            "        return " + className + ".TEMPLATE_ID;\n" +
            "    }\n\n" +
            "    public sbeSchemaId(): %17$s\n" +
            "    {\n" +
            "        return " + className + ".SCHEMA_ID;\n" +
            "    }\n\n" +
            "    public sbeSchemaVersion(): %18$s\n" +
            "    {\n" +
            "        return " + className + ".SCHEMA_VERSION;\n" +
            "    }\n\n" +
            "    public sbeSemanticType(): string\n" +
            "    {\n" +
            "        return \"%10$s\";\n" +
            "    }\n\n" +
            "    public buffer(): %11$s\n" +
            "    {\n" +
            "        return this._buffer;\n" +
            "    }\n\n" +
            "    public initialOffset(): number\n" +
            "    {\n" +
            "        return this._initialOffset;\n" +
            "    }\n\n" +
            "    public offset(): number\n" +
            "    {\n" +
            "        return this._offset;\n" +
            "    }\n\n" +
            "%12$s" +
            "    public encodedLength(): number\n" +
            "    {\n" +
            "        return this._limit - this._offset;\n" +
            "    }\n\n" +
            "    public getLimit(): number\n" +
            "    {\n" +
            "        return this._limit;\n" +
            "    }\n\n" +
            "    public setLimit(limit: number)\n" +
            "    {\n" +
            "        this._limit = limit;\n" +
            "    }\n",
            blockLengthType,
            generateLiteral(headerStructure.blockLengthType(), Integer.toString(token.encodedLength())),
            templateIdType,
            generateLiteral(headerStructure.templateIdType(), Integer.toString(token.id())),
            schemaIdType,
            generateLiteral(headerStructure.schemaIdType(), Integer.toString(ir.id())),
            schemaVersionType,
            generateLiteral(headerStructure.schemaVersionType(), Integer.toString(ir.version())),
            className,
            semanticType,
            bufferImplementation,
            wrapMethod,
            actingFields,
            ir.byteOrder(),
            blockLengthAccessorType,
            templateIdAccessorType,
            schemaIdAccessorType,
            schemaVersionAccessorType,
            semanticVersion);
    }

    private CharSequence generateEncoderFlyweightCode(final String className, final Token token)
    {
        final String wrapMethod =
            "    public wrap(buffer: " + mutableBuffer + ", offset: number): " + className + "\n" +
            "    {\n" +
            "        if (buffer != this._buffer)\n" +
            "        {\n" +
            "            this._buffer = buffer;\n" +
            "        }\n" +
            "        this._initialOffset = offset;\n" +
            "        this._offset = offset;\n" +
            "        this.setLimit(offset + " + className + ".BLOCK_LENGTH);\n\n" +
            "        return this;\n" +
            "    }\n\n";

        final StringBuilder builder = new StringBuilder(
            "    public wrapAndApplyHeader(\n" +
            "        buffer: %2$s, offset: number, headerEncoder: %3$s): %1$s\n" +
            "    {\n" +
            "        headerEncoder\n" +
            "            .wrap(buffer, offset)");

        for (final Token headerToken : ir.headerStructure().tokens())
        {
            if (!headerToken.isConstantEncoding())
            {
                switch (headerToken.name())
                {
                    case "blockLength":
                        builder.append("\n            .blockLength(" + className + ".BLOCK_LENGTH)");
                        break;

                    case "templateId":
                        builder.append("\n            .templateId(" + className + ".TEMPLATE_ID)");
                        break;

                    case "schemaId":
                        builder.append("\n            .schemaId(" + className + ".SCHEMA_ID)");
                        break;

                    case "version":
                        builder.append("\n            .version(" + className + ".SCHEMA_VERSION)");
                        break;
                }
            }
        }

        builder.append(";\n\n        return this.wrap(buffer, offset + %3$s.ENCODED_LENGTH);\n" + "    }\n\n");

        final String wrapAndApplyMethod = String.format(
            builder.toString(),
            className,
            mutableBuffer,
            formatClassName(ir.headerStructure().tokens().get(0).applicableTypeName() + "Encoder"));

        return generateFlyweightCode(
            CodecType.ENCODER, className, token, wrapMethod + wrapAndApplyMethod, mutableBuffer);
    }

    private void generateEncoderFields(
        final StringBuilder sb, final String containingClassName, final List<Token> tokens, final String indent)
    {
        Generators.forEachField(
            tokens,
            (fieldToken, typeToken) ->
            {
                final String propertyName = formatPropertyName(fieldToken.name());
                final String typeName = encoderName(typeToken.name());

                generateFieldIdMethod(sb, fieldToken, indent);
                generateFieldSinceVersionMethod(sb, fieldToken, indent);
                generateEncodingOffsetMethod(sb, propertyName, fieldToken.offset(), indent);
                generateEncodingLengthMethod(sb, propertyName, typeToken.encodedLength(), indent);
                generateFieldMetaAttributeMethod(sb, fieldToken, indent);

                switch (typeToken.signal())
                {
                    case ENCODING:
                        generatePrimitiveEncoder(sb, containingClassName, propertyName, typeToken, indent);
                        break;

                    case BEGIN_ENUM:
                        generateEnumEncoder(sb, containingClassName, fieldToken, propertyName, typeToken, indent);
                        break;

                    case BEGIN_SET:
                        generateBitSetProperty(
                            sb, false, ENCODER, propertyName, fieldToken, typeToken, indent, typeName);
                        break;

                    case BEGIN_COMPOSITE:
                        generateCompositeProperty(
                            sb, false, ENCODER, propertyName, fieldToken, typeToken, indent, typeName);
                        break;

                    default:
                        break;
                }
            });
    }

    private void generateDecoderFields(final StringBuilder sb, final List<Token> tokens, final String indent)
    {
        Generators.forEachField(
            tokens,
            (fieldToken, typeToken) ->
            {
                final String propertyName = formatPropertyName(fieldToken.name());
                final String typeName = decoderName(typeToken.name());

                generateFieldIdMethod(sb, fieldToken, indent);
                generateFieldSinceVersionMethod(sb, fieldToken, indent);
                generateEncodingOffsetMethod(sb, propertyName, fieldToken.offset(), indent);
                generateEncodingLengthMethod(sb, propertyName, typeToken.encodedLength(), indent);
                generateFieldMetaAttributeMethod(sb, fieldToken, indent);

                switch (typeToken.signal())
                {
                    case ENCODING:
                        generatePrimitiveDecoder(sb, false, propertyName, fieldToken, typeToken, indent);
                        break;

                    case BEGIN_ENUM:
                        generateEnumDecoder(sb, false, fieldToken, propertyName, typeToken, indent);
                        break;

                    case BEGIN_SET:
                        generateBitSetProperty(
                            sb, false, DECODER, propertyName, fieldToken, typeToken, indent, typeName);
                        break;

                    case BEGIN_COMPOSITE:
                        generateCompositeProperty(
                            sb, false, DECODER, propertyName, fieldToken, typeToken, indent, typeName);
                        break;

                    default:
                        break;
                }
            });
    }

    private static void generateFieldIdMethod(final StringBuilder sb, final Token token, final String indent)
    {
        final String propertyName = formatPropertyName(token.name());
        sb.append("\n")
            .append(indent).append("    public static ").append(propertyName).append("Id(): number\n")
            .append(indent).append("    {\n")
            .append(indent).append("        return ").append(token.id()).append(";\n")
            .append(indent).append("    }\n");
    }

    private static void generateEncodingOffsetMethod(
        final StringBuilder sb, final String name, final int offset, final String indent)
    {
        final String propertyName = formatPropertyName(name);
        sb.append("\n")
            .append(indent).append("    public static ").append(propertyName).append("EncodingOffset(): number\n")
            .append(indent).append("    {\n")
            .append(indent).append("        return ").append(offset).append(";\n")
            .append(indent).append("    }\n");
    }

    private static void generateEncodingLengthMethod(
        final StringBuilder sb, final String name, final int length, final String indent)
    {
        final String propertyName = formatPropertyName(name);
        sb.append("\n")
            .append(indent).append("    public static ").append(propertyName).append("EncodingLength(): number\n")
            .append(indent).append("    {\n")
            .append(indent).append("        return ").append(length).append(";\n")
            .append(indent).append("    }\n");
    }

    private static void generateFieldSinceVersionMethod(final StringBuilder sb, final Token token, final String indent)
    {
        final String propertyName = formatPropertyName(token.name());
        sb.append("\n")
            .append(indent).append("    public static ").append(propertyName).append("SinceVersion(): number\n")
            .append(indent).append("    {\n")
            .append(indent).append("        return ").append(token.version()).append(";\n")
            .append(indent).append("    }\n");
    }

    private static void generateFieldMetaAttributeMethod(final StringBuilder sb, final Token token, final String indent)
    {
        final Encoding encoding = token.encoding();
        final String epoch = encoding.epoch() == null ? "" : encoding.epoch();
        final String timeUnit = encoding.timeUnit() == null ? "" : encoding.timeUnit();
        final String semanticType = encoding.semanticType() == null ? "" : encoding.semanticType();
        final String presence = encoding.presence().toString().toLowerCase();
        final String propertyName = formatPropertyName(token.name());

        sb.append("\n")
            .append(indent).append("    public static ")
            .append(propertyName).append("MetaAttribute(metaAttribute: MetaAttribute): string\n")
            .append(indent).append("    {\n")
            .append(indent).append("        if (MetaAttribute.PRESENCE == metaAttribute)\n")
            .append(indent).append("        {\n")
            .append(indent).append("            return \"").append(presence).append("\";\n")
            .append(indent).append("        }\n");

        if (!Strings.isEmpty(epoch))
        {
            sb.append(indent).append("        if (MetaAttribute.EPOCH == metaAttribute)\n")
                .append(indent).append("        {\n")
                .append(indent).append("            return \"").append(epoch).append("\";\n")
                .append(indent).append("        }\n");
        }

        if (!Strings.isEmpty(timeUnit))
        {
            sb.append(indent).append("        if (MetaAttribute.TIME_UNIT == metaAttribute)\n")
                .append(indent).append("        {\n")
                .append(indent).append("            return \"").append(timeUnit).append("\";\n")
                .append(indent).append("        }\n");
        }

        if (!Strings.isEmpty(semanticType))
        {
            sb.append(indent).append("        if (MetaAttribute.SEMANTIC_TYPE == metaAttribute)\n")
                .append(indent).append("        {\n")
                .append(indent).append("            return \"").append(semanticType).append("\";\n")
                .append(indent).append("        }\n");
        }

        sb.append("\n")
            .append(indent).append("        return \"\";\n")
            .append(indent).append("    }\n");
    }

    private void generateEnumDecoder(
        final StringBuilder sb,
        final boolean inComposite,
        final Token fieldToken,
        final String propertyName,
        final Token typeToken,
        final String indent)
    {
        final String enumName = formatClassName(typeToken.applicableTypeName());
        final Encoding encoding = typeToken.encoding();
        final String javaTypeName = typeScriptTypeName(encoding.primitiveType());

        if (fieldToken.isConstantEncoding())
        {
            final String enumValueStr = fieldToken.encoding().constValue().toString();

            new Formatter(sb).format(
                "\n" +
                indent + "    public %sRaw(): %s\n" +
                indent + "    {\n" +
                indent + "        return %s.value();\n" +
                indent + "    }\n\n",
                propertyName,
                javaTypeName,
                enumValueStr);

            new Formatter(sb).format(
                "\n" +
                indent + "    public %s(): %s\n" +
                indent + "    {\n" +
                indent + "        return %s;\n" +
                indent + "    }\n\n",
                propertyName,
                enumName,
                enumValueStr);
        }
        else
        {
            final String rawGetStr = generateGet(
                encoding.primitiveType(), "offset + " + typeToken.offset(), byteOrderString(encoding));

            new Formatter(sb).format(
                "\n" +
                indent + "    public %sRaw(): %s\n" +
                indent + "    {\n" +
                "%s" +
                indent + "        return %s;\n" +
                indent + "    }\n",
                formatPropertyName(propertyName),
                javaTypeName,
                generateFieldNotPresentCondition(inComposite, fieldToken.version(), encoding, indent),
                rawGetStr);

            new Formatter(sb).format(
                "\n" +
                indent + "    public %s(): %s\n" +
                indent + "    {\n" +
                "%s" +
                indent + "        return %s.get(%s);\n" +
                indent + "    }\n\n",
                propertyName,
                enumName,
                generatePropertyNotPresentCondition(inComposite, DECODER, fieldToken, enumName, indent),
                enumName,
                rawGetStr);
        }
    }

    private void generateEnumEncoder(
        final StringBuilder sb,
        final String containingClassName,
        final Token fieldToken,
        final String propertyName,
        final Token typeToken,
        final String indent)
    {
        if (!fieldToken.isConstantEncoding())
        {
            final String enumName = formatClassName(typeToken.applicableTypeName());
            final Encoding encoding = typeToken.encoding();
            final int offset = typeToken.offset();
            final String byteOrderString = byteOrderString(encoding);

            new Formatter(sb).format("\n" +
                indent + "    public %s(value: %s): %s\n" +
                indent + "    {\n" +
                indent + "        %s;\n" +
                indent + "        return this;\n" +
                indent + "    }\n",
                propertyName,
                formatClassName(containingClassName),
                enumName,
                generatePut(encoding.primitiveType(), "this._offset + " + offset, "value.value()", byteOrderString));
        }
    }

    private void generateBitSetProperty(
        final StringBuilder sb,
        final boolean inComposite,
        final CodecType codecType,
        final String propertyName,
        final Token propertyToken,
        final Token bitsetToken,
        final String indent,
        final String bitSetName)
    {
        new Formatter(sb).format("\n" +
            indent + "    private _%s: %s = new %s();\n",
            propertyName,
            bitSetName,
            bitSetName);

        generateFlyweightPropertyJavadoc(sb, indent + INDENT, propertyToken, bitSetName);
        new Formatter(sb).format("\n" +
            indent + "    public %s(): %s\n" +
            indent + "    {\n" +
            "%s" +
            indent + "        this._%s.wrap(this._buffer, this._offset + %d);\n" +
            indent + "        return this._%s;\n" +
            indent + "    }\n",
            propertyName,
            bitSetName,
            generatePropertyNotPresentCondition(inComposite, codecType, propertyToken, null, indent),
            propertyName,
            bitsetToken.offset(),
            propertyName);
    }

    private void generateCompositeProperty(
        final StringBuilder sb,
        final boolean inComposite,
        final CodecType codecType,
        final String propertyName,
        final Token propertyToken,
        final Token compositeToken,
        final String indent,
        final String compositeName)
    {
        new Formatter(sb).format("\n" +
            indent + "    private _%s: %s = new %s();\n",
            propertyName,
            compositeName,
            compositeName);

        generateFlyweightPropertyJavadoc(sb, indent + INDENT, propertyToken, compositeName);
        new Formatter(sb).format("\n" +
            indent + "    public %s(): %s\n" +
            indent + "    {\n" +
            "%s" +
            indent + "        this._%s.wrap(this._buffer, this._offset + %d);\n" +
            indent + "        return this._%s;\n" +
            indent + "    }\n",
            propertyName,
            compositeName,
            generatePropertyNotPresentCondition(inComposite, codecType, propertyToken, null, indent),
            propertyName,
            compositeToken.offset(),
            propertyName);
    }

//todo: convert to ts
    private String generateGet(final PrimitiveType type, final String index, final String byteOrder)
    {
        switch (type)
        {
            case CHAR:
            case INT8:
                return "this._buffer.getByte(" + index + ")";

            case UINT8:
                return "((short)(this._buffer.getByte(" + index + ") & 0xFF))";

            case INT16:
                return "this._buffer.getShort(" + index + byteOrder + ")";

            case UINT16:
                return "(this._buffer.getShort(" + index + byteOrder + ") & 0xFFFF)";

            case INT32:
                return "this._buffer.getInt(" + index + byteOrder + ")";

            case UINT32:
                return "(this._buffer.getInt(" + index + byteOrder + ") & 0xFFFF_FFFFL)";

            case FLOAT:
                return "this._buffer.getFloat(" + index + byteOrder + ")";

            case INT64:
            case UINT64:
                return "this._buffer.getLong(" + index + byteOrder + ")";

            case DOUBLE:
                return "this._buffer.getDouble(" + index + byteOrder + ")";

            default:
                break;
        }

        throw new IllegalArgumentException("primitive type not supported: " + type);
    }

//todo: convert to ts
    private String generatePut(
        final PrimitiveType type, final String index, final String value, final String byteOrder)
    {
        switch (type)
        {
            case CHAR:
            case INT8:
                return "this._buffer.putByte(" + index + ", " + value + ")";

            case UINT8:
                return "this._buffer.putByte(" + index + ", (byte)" + value + ")";

            case INT16:
                return "this._buffer.putShort(" + index + ", " + value + byteOrder + ")";

            case UINT16:
                return "this._buffer.putShort(" + index + ", (short)" + value + byteOrder + ")";

            case INT32:
                return "this._buffer.putInt(" + index + ", " + value + byteOrder + ")";

            case UINT32:
                return "this._buffer.putInt(" + index + ", (int)" + value + byteOrder + ")";

            case FLOAT:
                return "this._buffer.putFloat(" + index + ", " + value + byteOrder + ")";

            case INT64:
            case UINT64:
                return "this._buffer.putLong(" + index + ", " + value + byteOrder + ")";

            case DOUBLE:
                return "this._buffer.putDouble(" + index + ", " + value + byteOrder + ")";

            default:
                break;
        }

        throw new IllegalArgumentException("primitive type not supported: " + type);
    }

    private String generateChoiceIsEmpty(final PrimitiveType type)
    {
        return "\n" +
            "    public isEmpty(): boolean\n" +
            "    {\n" +
            "        return " + generateChoiceIsEmptyInner(type) + ";\n" +
            "    }\n";
    }

    private String generateChoiceIsEmptyInner(final PrimitiveType type)
    {
        switch (type)
        {
            case UINT8:
                return "0 == this._buffer.getByte(this._offset)";

            case UINT16:
                return "0 == this._buffer.getShort(this._offset)";

            case UINT32:
                return "0 == this._buffer.getInt(this._offset)";

            case UINT64:
                return "0 == this._buffer.getLong(this._offset)";

            default:
                break;
        }

        throw new IllegalArgumentException("primitive type not supported: " + type);
    }

    private String generateChoiceGet(final PrimitiveType type, final String bitIndex, final String byteOrder)
    {
        switch (type)
        {
            case UINT8:
                return "0 != (this._buffer.getByte(this._offset) & (1 << " + bitIndex + "))";

            case UINT16:
                return "0 != (this._buffer.getShort(this._offset" + byteOrder + ") & (1 << " + bitIndex + "))";

            case UINT32:
                return "0 != (this._buffer.getInt(this._offset" + byteOrder + ") & (1 << " + bitIndex + "))";

            case UINT64:
                return "0 != (this._buffer.getLong(this._offset" + byteOrder + ") & (1L << " + bitIndex + "))";

            default:
                break;
        }

        throw new IllegalArgumentException("primitive type not supported: " + type);
    }

    private String generateStaticChoiceGet(final PrimitiveType type, final String bitIndex)
    {
        switch (type)
        {
            case UINT8:
            case UINT16:
            case UINT32:
                return "0 != (value & (1 << " + bitIndex + "))";

            case UINT64:
                return "0 != (value & (1n << " + bitIndex + "n))";

            default:
                break;
        }

        throw new IllegalArgumentException("primitive type not supported: " + type);
    }

//todo: convert to TS
    private String generateChoicePut(final PrimitiveType type, final String bitIdx, final String byteOrder)
    {
        switch (type)
        {
            case UINT8:
                return
                    "        let bits: number = buffer.getByte(this._offset);\n" +
                    "        bits = (byte)(value ? bits | (1 << " + bitIdx + ") : bits & ~(1 << " + bitIdx + "));\n" +
                    "        this._buffer.putByte(this._offset, bits);";

            case UINT16:
                return
                    "        short bits = buffer.getShort(this._offset" + byteOrder + ");\n" +
                    "        bits = (short)(value ? bits | (1 << " + bitIdx + ") : bits & ~(1 << " + bitIdx + "));\n" +
                    "        this._buffer.putShort(this._offset, bits" + byteOrder + ");";

            case UINT32:
                return
                    "        int bits = buffer.getInt(this._offset" + byteOrder + ");\n" +
                    "        bits = value ? bits | (1 << " + bitIdx + ") : bits & ~(1 << " + bitIdx + ");\n" +
                    "        this._buffer.putInt(this._offset, bits" + byteOrder + ");";

            case UINT64:
                return
                    "        long bits = buffer.getLong(this._offset" + byteOrder + ");\n" +
                    "        bits = value ? bits | (1L << " + bitIdx + ") : bits & ~(1L << " + bitIdx + ");\n" +
                    "        this._buffer.putLong(this._offset, bits" + byteOrder + ");";

            default:
                break;
        }

        throw new IllegalArgumentException("primitive type not supported: " + type);
    }

//todo: convert to TS
    private String generateStaticChoicePut(final PrimitiveType type, final String bitIdx)
    {
        switch (type)
        {
            case UINT8:
                return
                    "        return (byte)(value ? bits | (1 << " + bitIdx + ") : bits & ~(1 << " + bitIdx + "));\n";

            case UINT16:
                return
                    "        return (short)(value ? bits | (1 << " + bitIdx + ") : bits & ~(1 << " + bitIdx + "));\n";

            case UINT32:
                return
                    "        return value ? bits | (1 << " + bitIdx + ") : bits & ~(1 << " + bitIdx + ");\n";

            case UINT64:
                return
                    "        return value ? bits | (1L << " + bitIdx + ") : bits & ~(1L << " + bitIdx + ");\n";

            default:
                break;
        }

        throw new IllegalArgumentException("primitive type not supported: " + type);
    }

    private void generateEncoderDisplay(final StringBuilder sb, final String decoderName)
    {
        appendToString(sb);

        sb.append('\n');
        append(sb, INDENT, "public appendTo(builder: StringBuilder): StringBuilder");
        append(sb, INDENT, "{");
        append(sb, INDENT, "    if (null == this._buffer)");
        append(sb, INDENT, "    {");
        append(sb, INDENT, "        return builder;");
        append(sb, INDENT, "    }");
        sb.append('\n');
        append(sb, INDENT, "    let decoder: " + decoderName + " = new " + decoderName + "();");
        append(sb, INDENT, "    decoder.wrap(this._buffer, this._initialOffset, ");
        append(sb, "", decoderName + ".BLOCK_LENGTH, " + decoderName + ".SCHEMA_VERSION);");
        sb.append('\n');
        append(sb, INDENT, "    return decoder.appendTo(builder);");
        append(sb, INDENT, "}");
    }

    private CharSequence generateCompositeEncoderDisplay(final String decoderName)
    {
        final StringBuilder sb = new StringBuilder();

        appendToString(sb);
        sb.append('\n');
        append(sb, INDENT, "public appendTo(builder: StringBuilder): StringBuilder");
        append(sb, INDENT, "{");
        append(sb, INDENT, "    if (null == this._buffer)");
        append(sb, INDENT, "    {");
        append(sb, INDENT, "        return builder;");
        append(sb, INDENT, "    }");
        sb.append('\n');
        append(sb, INDENT, "    let decoder: " + decoderName + " = new " + decoderName + "();");
        append(sb, INDENT, "    decoder.wrap(this._buffer, this._offset);");
        sb.append('\n');
        append(sb, INDENT, "    return decoder.appendTo(builder);");
        append(sb, INDENT, "}");

        return sb;
    }

    private CharSequence generateCompositeDecoderDisplay(final List<Token> tokens)
    {
        final StringBuilder sb = new StringBuilder();

        appendToString(sb);
        sb.append('\n');
        append(sb, INDENT, "public appendTo(builder: StringBuilder): StringBuilder");
        append(sb, INDENT, "{");
        append(sb, INDENT, "    if (null == this._buffer)");
        append(sb, INDENT, "    {");
        append(sb, INDENT, "        return builder;");
        append(sb, INDENT, "    }");
        sb.append('\n');
        Separator.BEGIN_COMPOSITE.appendToGeneratedBuilder(sb, INDENT + INDENT);

        int lengthBeforeLastGeneratedSeparator = -1;

        for (int i = 1, end = tokens.size() - 1; i < end;)
        {
            final Token encodingToken = tokens.get(i);
            final String propertyName = formatPropertyName(encodingToken.name());
            lengthBeforeLastGeneratedSeparator = writeTokenDisplay(propertyName, encodingToken, sb, INDENT + INDENT);
            i += encodingToken.componentTokenCount();
        }

        if (-1 != lengthBeforeLastGeneratedSeparator)
        {
            sb.setLength(lengthBeforeLastGeneratedSeparator);
        }

        Separator.END_COMPOSITE.appendToGeneratedBuilder(sb, INDENT + INDENT);
        sb.append('\n');
        append(sb, INDENT, "    return builder;");
        append(sb, INDENT, "}");

        return sb;
    }

    private CharSequence generateChoiceDisplay(final List<Token> tokens)
    {
        final StringBuilder sb = new StringBuilder();

        appendToString(sb);
        sb.append('\n');
        append(sb, INDENT, "public appendTo(builder: StringBuilder): StringBuilder");
        append(sb, INDENT, "{");
        Separator.BEGIN_SET.appendToGeneratedBuilder(sb, INDENT + INDENT);
        append(sb, INDENT, "    let atLeastOne: boolean = false;");

        for (final Token token : tokens)
        {
            if (token.signal() == Signal.CHOICE)
            {
                final String choiceName = formatPropertyName(token.name());
                append(sb, INDENT, "    if (" + choiceName + "())");
                append(sb, INDENT, "    {");
                append(sb, INDENT, "        if (atLeastOne)");
                append(sb, INDENT, "        {");
                Separator.ENTRY.appendToGeneratedBuilder(sb, INDENT + INDENT + INDENT + INDENT);
                append(sb, INDENT, "        }");
                append(sb, INDENT, "        builder.append(\"" + choiceName + "\");");
                append(sb, INDENT, "        atLeastOne = true;");
                append(sb, INDENT, "    }");
            }
        }

        Separator.END_SET.appendToGeneratedBuilder(sb, INDENT + INDENT);
        sb.append('\n');
        append(sb, INDENT, "    return builder;");
        append(sb, INDENT, "}");

        return sb;
    }

    private void generateDecoderDisplay(
        final StringBuilder sb,
        final String name,
        final List<Token> tokens,
        final List<Token> groups,
        final List<Token> varData)
    {
        appendMessageToString(sb, decoderName(name));
        sb.append('\n');
        append(sb, INDENT, "public appendTo(builder: StringBuilder): StringBuilder");
        append(sb, INDENT, "{");
        append(sb, INDENT, "    if (null == this._buffer)");
        append(sb, INDENT, "    {");
        append(sb, INDENT, "        return builder;");
        append(sb, INDENT, "    }");
        sb.append('\n');
        append(sb, INDENT, "    let originalLimit: number = this.getLimit();");
        append(sb, INDENT, "    this.setLimit(this._initialOffset + this._actingBlockLength);");
        append(sb, INDENT, "    builder.append(\"[" + name + "](sbeTemplateId=\");");
        append(sb, INDENT, "    builder.append(" + name + ".TEMPLATE_ID);");
        append(sb, INDENT, "    builder.append(\"|sbeSchemaId=\");");
        append(sb, INDENT, "    builder.append(" + name + ".SCHEMA_ID);");
        append(sb, INDENT, "    builder.append(\"|sbeSchemaVersion=\");");
        append(sb, INDENT, "    if (this._parentMessage.actingVersion() != " + name + ".SCHEMA_VERSION)");
        append(sb, INDENT, "    {");
        append(sb, INDENT, "        builder.append(this._parentMessage.actingVersion());");
        append(sb, INDENT, "        builder.append('/');");
        append(sb, INDENT, "    }");
        append(sb, INDENT, "    builder.append(" + name + ".SCHEMA_VERSION);");
        append(sb, INDENT, "    builder.append(\"|sbeBlockLength=\");");
        append(sb, INDENT, "    if (this._actingBlockLength != " + name + ".BLOCK_LENGTH)");
        append(sb, INDENT, "    {");
        append(sb, INDENT, "        builder.append(this._actingBlockLength);");
        append(sb, INDENT, "        builder.append('/');");
        append(sb, INDENT, "    }");
        append(sb, INDENT, "    builder.append(" + name + ".BLOCK_LENGTH);");
        append(sb, INDENT, "    builder.append(\"):\");");
        appendDecoderDisplay(sb, tokens, groups, varData, INDENT + INDENT);
        sb.append('\n');
        append(sb, INDENT, "    this.setLimit(originalLimit);");
        sb.append('\n');
        append(sb, INDENT, "    return builder;");
        append(sb, INDENT, "}");
    }

    private void appendGroupInstanceDecoderDisplay(
        final StringBuilder sb,
        final List<Token> fields,
        final List<Token> groups,
        final List<Token> varData,
        final String baseIndent)
    {
        final String indent = baseIndent + INDENT;

        sb.append('\n');
        append(sb, indent, "public appendTo(builder: StringBuilder): StringBuilder");
        append(sb, indent, "{");
        append(sb, indent, "    if (null == this._buffer)");
        append(sb, indent, "    {");
        append(sb, indent, "        return builder;");
        append(sb, indent, "    }");
        sb.append('\n');
        Separator.BEGIN_COMPOSITE.appendToGeneratedBuilder(sb, indent + INDENT);
        appendDecoderDisplay(sb, fields, groups, varData, indent + INDENT);
        Separator.END_COMPOSITE.appendToGeneratedBuilder(sb, indent + INDENT);
        sb.append('\n');
        append(sb, indent, "    return builder;");
        append(sb, indent, "}");
    }

    private void appendDecoderDisplay(
        final StringBuilder sb,
        final List<Token> fields,
        final List<Token> groups,
        final List<Token> varData,
        final String indent)
    {
        int lengthBeforeLastGeneratedSeparator = -1;

        for (int i = 0, size = fields.size(); i < size;)
        {
            final Token fieldToken = fields.get(i);
            if (fieldToken.signal() == Signal.BEGIN_FIELD)
            {
                final Token encodingToken = fields.get(i + 1);
                final String fieldName = formatPropertyName(fieldToken.name());
                lengthBeforeLastGeneratedSeparator = writeTokenDisplay(fieldName, encodingToken, sb, indent);

                i += fieldToken.componentTokenCount();
            }
            else
            {
                ++i;
            }
        }

        for (int i = 0, size = groups.size(); i < size; i++)
        {
            final Token groupToken = groups.get(i);
            if (groupToken.signal() != Signal.BEGIN_GROUP)
            {
                throw new IllegalStateException("tokens must begin with BEGIN_GROUP: token=" + groupToken);
            }

            final String groupName = formatPropertyName(groupToken.name());
            final String groupDecoderName = decoderName(groupToken.name());

            append(
                sb, indent, "builder.append(\"" + groupName + Separator.KEY_VALUE + Separator.BEGIN_GROUP + "\");");
            append(sb, indent, "let " + groupName + "OriginalOffset: number = " + groupName + ".offset;");
            append(sb, indent, "let " + groupName + "OriginalIndex: number = " + groupName + ".index;");
            append(sb, indent, "let " + groupName + ":" + groupDecoderName + " = this." + groupName + "();");

            append(sb, indent, "if (" + groupName + ".count() > 0)");
            append(sb, indent, "{");
            append(sb, indent, "    while (" + groupName + ".hasNext())");
            append(sb, indent, "    {");
            append(sb, indent, "        " + groupName + ".next().appendTo(builder);");
            Separator.ENTRY.appendToGeneratedBuilder(sb, indent + INDENT + INDENT);
            append(sb, indent, "    }");
            append(sb, indent, "    builder.setLength(builder.length() - 1);");
            append(sb, indent, "}");

            append(sb, indent, groupName + ".offset = " + groupName + "OriginalOffset;");
            append(sb, indent, groupName + ".index = " + groupName + "OriginalIndex;");
            Separator.END_GROUP.appendToGeneratedBuilder(sb, indent);


            lengthBeforeLastGeneratedSeparator = sb.length();
            Separator.FIELD.appendToGeneratedBuilder(sb, indent);

            i = findEndSignal(groups, i, Signal.END_GROUP, groupToken.name());
        }

        for (int i = 0, size = varData.size(); i < size;)
        {
            final Token varDataToken = varData.get(i);
            if (varDataToken.signal() != Signal.BEGIN_VAR_DATA)
            {
                throw new IllegalStateException("tokens must begin with BEGIN_VAR_DATA: token=" + varDataToken);
            }

            final String characterEncoding = varData.get(i + 3).encoding().characterEncoding();
            final String varDataName = formatPropertyName(varDataToken.name());
            append(sb, indent, "builder.append(\"" + varDataName + Separator.KEY_VALUE + "\");");
            if (null == characterEncoding)
            {
                final String name = Generators.toUpperFirstChar(varDataToken.name());
                append(sb, indent, "builder.append(skip" + name + "()).append(\" bytes of raw data\");");
            }
            else
            {
                if (isAsciiEncoding(characterEncoding))
                {
                    append(sb, indent, "builder.append('\\'');");
                    append(sb, indent, formatGetterName(varDataToken.name()) + "(builder);");
                    append(sb, indent, "builder.append('\\'');");
                }
                else
                {
                    append(sb, indent, "builder.append('\\'').append(" + varDataName + "()).append('\\'');");
                }
            }

            lengthBeforeLastGeneratedSeparator = sb.length();
            Separator.FIELD.appendToGeneratedBuilder(sb, indent);

            i += varDataToken.componentTokenCount();
        }

        if (-1 != lengthBeforeLastGeneratedSeparator)
        {
            sb.setLength(lengthBeforeLastGeneratedSeparator);
        }
    }

    private int writeTokenDisplay(
        final String fieldName, final Token typeToken, final StringBuilder sb, final String indent)
    {
        if (typeToken.encodedLength() <= 0 || typeToken.isConstantEncoding())
        {
            return -1;
        }

        append(sb, indent, "builder.append(\"" + fieldName + Separator.KEY_VALUE + "\");");

        switch (typeToken.signal())
        {
            case ENCODING:
                if (typeToken.arrayLength() > 1)
                {
                    if (typeToken.encoding().primitiveType() == PrimitiveType.CHAR)
                    {
                        append(sb, indent,
                            "for (let i = 0; i < " + fieldName + "Length() && this." + fieldName + "(i) > 0; i++)");
                        append(sb, indent, "{");
                        append(sb, indent, "    builder.append((char)this." + fieldName + "(i));");
                        append(sb, indent, "}");
                    }
                    else
                    {
                        Separator.BEGIN_ARRAY.appendToGeneratedBuilder(sb, indent);
                        append(sb, indent, "if (" + fieldName + "Length() > 0)");
                        append(sb, indent, "{");
                        append(sb, indent, "    for (let i = 0; i < " + fieldName + "Length(); i++)");
                        append(sb, indent, "    {");
                        append(sb, indent, "        builder.append(this." + fieldName + "(i));");
                        Separator.ENTRY.appendToGeneratedBuilder(sb, indent + INDENT + INDENT);
                        append(sb, indent, "    }");
                        append(sb, indent, "    builder.setLength(builder.length() - 1);");
                        append(sb, indent, "}");
                        Separator.END_ARRAY.appendToGeneratedBuilder(sb, indent);
                    }
                }
                else
                {
                    // have to duplicate because of checkstyle :/
                    append(sb, indent, "builder.append(this." + fieldName + "());");
                }
                break;

            case BEGIN_ENUM:
                append(sb, indent, "builder.append(this." + fieldName + "());");
                break;

            case BEGIN_SET:
                append(sb, indent, "this." + fieldName + "().appendTo(builder);");
                break;

            case BEGIN_COMPOSITE:
            {
                final String typeName = formatClassName(decoderName(typeToken.applicableTypeName()));
                append(sb, indent, "let " + fieldName + ": " + typeName + " = this." + fieldName + "();");
                append(sb, indent, "if (" + fieldName + " != null)");
                append(sb, indent, "{");
                append(sb, indent, "    " + fieldName + ".appendTo(builder);");
                append(sb, indent, "}");
                append(sb, indent, "else");
                append(sb, indent, "{");
                append(sb, indent, "    builder.append(\"null\");");
                append(sb, indent, "}");
                break;
            }

            default:
                break;
        }

        final int lengthBeforeFieldSeparator = sb.length();
        Separator.FIELD.appendToGeneratedBuilder(sb, indent);

        return lengthBeforeFieldSeparator;
    }

    private void appendToString(final StringBuilder sb)
    {
        sb.append('\n');
        append(sb, INDENT, "public toString(): string");
        append(sb, INDENT, "{");
        append(sb, INDENT, "    if (null == this._buffer)");
        append(sb, INDENT, "    {");
        append(sb, INDENT, "        return \"\";");
        append(sb, INDENT, "    }");
        sb.append('\n');
        append(sb, INDENT, "    return this.appendTo(new StringBuilder()).toString();");
        append(sb, INDENT, "}");
    }

    private void appendMessageToString(final StringBuilder sb, final String decoderName)
    {
        sb.append('\n');
        append(sb, INDENT, "public toString(): string");
        append(sb, INDENT, "{");
        append(sb, INDENT, "    if (null == this._buffer)");
        append(sb, INDENT, "    {");
        append(sb, INDENT, "        return \"\";");
        append(sb, INDENT, "    }");
        sb.append('\n');
        append(sb, INDENT, "    let decoder: " + decoderName + " = new " + decoderName + "();");
        append(sb, INDENT, "    decoder.wrap(this._buffer, this._initialOffset, ");
        append(sb, "", "this._actingBlockLength, this._actingVersion);");
        sb.append('\n');
        append(sb, INDENT, "    return decoder.appendTo(new StringBuilder()).toString();");
        append(sb, INDENT, "}");
    }

    private void generateMessageLength(
        final StringBuilder sb,
        final String className,
        final boolean isParent,
        final List<Token> groups,
        final List<Token> varData,
        final String baseIndent)
    {
        final String methodIndent = baseIndent + INDENT;
        final String bodyIndent = methodIndent + INDENT;

        append(sb, methodIndent, "");
        append(sb, methodIndent, "public sbeSkip(): " + className);
        append(sb, methodIndent, "{");

        if (isParent)
        {
            append(sb, bodyIndent, "this.sbeRewind();");
        }

        for (int i = 0, size = groups.size(); i < size; i++)
        {
            final Token groupToken = groups.get(i);
            if (groupToken.signal() != Signal.BEGIN_GROUP)
            {
                throw new IllegalStateException("tokens must begin with BEGIN_GROUP: token=" + groupToken);
            }

            final String groupName = formatPropertyName(groupToken.name());
            final String groupDecoderName = decoderName(groupToken.name());

            append(sb, bodyIndent, "let " + groupName + ": " + groupDecoderName + " = this." + groupName + "();");
            append(sb, bodyIndent, "if (" + groupName + ".count() > 0)");
            append(sb, bodyIndent, "{");
            append(sb, bodyIndent, "    while (" + groupName + ".hasNext())");
            append(sb, bodyIndent, "    {");
            append(sb, bodyIndent, "        " + groupName + ".next();");
            append(sb, bodyIndent, "        " + groupName + ".sbeSkip();");
            append(sb, bodyIndent, "    }");
            append(sb, bodyIndent, "}");
            i = findEndSignal(groups, i, Signal.END_GROUP, groupToken.name());
        }

        for (int i = 0, size = varData.size(); i < size;)
        {
            final Token varDataToken = varData.get(i);
            if (varDataToken.signal() != Signal.BEGIN_VAR_DATA)
            {
                throw new IllegalStateException("tokens must begin with BEGIN_VAR_DATA: token=" + varDataToken);
            }

            final String varDataName = formatPropertyName(varDataToken.name());
            append(sb, bodyIndent, "skip" + Generators.toUpperFirstChar(varDataName) + "();");

            i += varDataToken.componentTokenCount();
        }

        sb.append('\n');
        append(sb, bodyIndent, "return this;");
        append(sb, methodIndent, "}");
    }

    private static String validateBufferImplementation(
        final String fullyQualifiedBufferImplementation, final Class<?> bufferClass)
    {
        Verify.notNull(fullyQualifiedBufferImplementation, "fullyQualifiedBufferImplementation");

        try
        {
            final Class<?> clazz = Class.forName(fullyQualifiedBufferImplementation);
            if (!bufferClass.isAssignableFrom(clazz))
            {
                throw new IllegalArgumentException(
                    fullyQualifiedBufferImplementation + " doesn't implement " + bufferClass.getName());
            }

            return clazz.getSimpleName();
        }
        catch (final ClassNotFoundException ex)
        {
            throw new IllegalArgumentException("Unable to find " + fullyQualifiedBufferImplementation, ex);
        }
    }

    private String encoderName(final String className)
    {
        return formatClassName(className) + "Encoder";
    }

    private String decoderName(final String className)
    {
        return formatClassName(className) + "Decoder";
    }

    private String implementsInterface(final String interfaceName)
    {
        return shouldGenerateInterfaces ? " implements " + interfaceName : "";
    }
}
