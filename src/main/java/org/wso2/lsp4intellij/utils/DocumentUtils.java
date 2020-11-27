/*
 * Copyright (c) 2019, WSO2 Inc. (http://www.wso2.org) All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.wso2.lsp4intellij.utils;

import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.editor.Document;
import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.editor.LogicalPosition;
import com.intellij.openapi.util.TextRange;
import com.intellij.openapi.util.text.StringUtil;
import com.intellij.psi.PsiDocumentManager;
import com.intellij.psi.PsiFile;
import com.intellij.psi.codeStyle.CommonCodeStyleSettings;
import com.intellij.util.DocumentUtil;
import org.eclipse.lsp4j.Position;

import javax.annotation.Nullable;

import static java.lang.Math.min;
import static org.wso2.lsp4intellij.utils.ApplicationUtils.computableReadAction;

/**
 * Various methods to convert offsets / logical position / server position
 */
public class DocumentUtils {

    private static Logger LOG = Logger.getInstance(DocumentUtils.class);
    public static final String WIN_SEPARATOR = "\r\n";
    public static final String LINUX_SEPARATOR = "\n";

    // maybe its too sophisticated.. otherwise just use: editor.getSettings().getTabSize(editor.getProject())
    public static int getTabSize(Editor editor) {
        return ApplicationUtils.computableReadAction(() ->{
            PsiFile psifile = PsiDocumentManager.getInstance(editor.getProject()).getPsiFile(editor.getDocument());
            CommonCodeStyleSettings commonCodeStyleSettings = new CommonCodeStyleSettings(psifile.getLanguage());
            int tabSize;
            final CommonCodeStyleSettings.IndentOptions indentOptions = commonCodeStyleSettings.getIndentOptions();
            if(indentOptions != null) {
                tabSize = indentOptions.TAB_SIZE;
            }else{
                tabSize = editor.getSettings().getTabSize(editor.getProject());
            }
            return tabSize;
        });
    }

    /**
     * Gets the line at the given offset given an editor and bolds the text between the given offsets
     *
     * @param editor      The editor
     * @param startOffset The starting offset
     * @param endOffset   The ending offset
     * @return The document line
     */
    public static String getLineText(Editor editor, int startOffset, int endOffset) {
        return computableReadAction(() -> {
            Document doc = editor.getDocument();
            int lineIdx = doc.getLineNumber(startOffset);
            int lineStartOff = doc.getLineStartOffset(lineIdx);
            int lineEndOff = doc.getLineEndOffset(lineIdx);
            String line = doc.getText(new TextRange(lineStartOff, lineEndOff));
            int startOffsetInLine = startOffset - lineStartOff;
            int endOffsetInLine = endOffset - lineStartOff;
            return computableReadAction(() -> line.substring(0, startOffsetInLine) + "<b>" + line
                    .substring(startOffsetInLine, endOffsetInLine) + "</b>" + line.substring(endOffsetInLine));
        });
    }

    /**
     * Transforms a LogicalPosition (IntelliJ) to an LSP Position
     *
     * @param position the LogicalPosition
     * @param editor   The editor
     * @return the Position
     */
    public static Position logicalToLSPPos(LogicalPosition position, Editor editor) {
        return offsetToLSPPos(editor, editor.logicalPositionToOffset(position));
    }

    /**
     * Transforms a LogicalPosition (IntelliJ) to an LSP Position
     *
     * @param position the LogicalPosition
     * @param editor   The editor
     * @return the Position
     */
    public static Position offsetToLSPPos(LogicalPosition position, Editor editor) {
        return offsetToLSPPos(editor, editor.logicalPositionToOffset(position));
    }

    /**
     * Calculates a Position given an editor and an offset
     *
     * @param editor The editor
     * @param offset The offset
     * @return an LSP position
     */
    public static Position offsetToLSPPos(Editor editor, int offset) {
        return computableReadAction(() -> {
            Document doc = editor.getDocument();
            int line = doc.getLineNumber(offset);
            int lineStart = doc.getLineStartOffset(line);
            String lineTextBeforeOffset = doc.getText(TextRange.create(lineStart, offset));

            int tabs = StringUtil.countChars(lineTextBeforeOffset, '\t');
            int tabSize = getTabSize(editor);
            int column = lineTextBeforeOffset.length() + tabs - tabs * tabSize ;
            return computableReadAction(() -> new Position(line, column));
        });
    }

    /**
     * Transforms an LSP position to an editor offset
     *
     * @param editor The editor
     * @param pos    The LSPPos
     * @return The offset
     */
    public static int LSPPosToOffset(Editor editor, Position pos) {
        return computableReadAction(() -> {
                if (editor == null) {
                    return -1;
                }
                if (editor.isDisposed()) {
                    return -2;
                }
                // lsp and intellij start lines/columns zero-based

                // FIXME: [ms] "The offsets are based on a UTF-16 string representation.
                //  So a string of the form a𐐀b the character offset of the character
                //  a is 0,
                //  the character offset of 𐐀 is 1
                //  and the character offset of b is 3 since 𐐀 is represented using two code units in UTF-16."
                // see https://microsoft.github.io/language-server-protocol/specifications/specification-current/#textDocuments

                Document doc = editor.getDocument();
                int line = Math.max(0, Math.min(pos.getLine(), doc.getLineCount()-1));
                String lineText = doc.getText(DocumentUtil.getLineTextRange(doc, line));

                final int endCharInLine = Math.max(0, min(lineText.length(), pos.getCharacter()));
                String lineTextForPosition = endCharInLine > 0 ? lineText.substring(0, endCharInLine) : "";
                int tabs = StringUtil.countChars(lineTextForPosition, '\t');
                int tabSize = getTabSize(editor);
                int column = tabs * tabSize - tabs + lineTextForPosition.length();
                int offset = editor.logicalPositionToOffset(new LogicalPosition(line, column));
                if (pos.getCharacter() >= lineText.length() && pos.getCharacter() != 0) {
                    LOG.warn(String.format("LSPPOS outofbounds for line: %s line : %s column : %d offset : %d", pos,
                            lineText, column, offset));
                }
                int docLength = doc.getTextLength();
                if (offset > docLength) {
                    LOG.warn(String.format("Offset greater than text length : %d > %d", offset, docLength));
                }
                return Math.min(Math.max(offset, 0), docLength);

        });
    }

    @Nullable
    public static LogicalPosition getTabsAwarePosition(Editor editor, Position pos) {
        return computableReadAction(() -> {
            try {
                if (editor.isDisposed()) {
                    return null;
                }
                Document doc = editor.getDocument();
                int line = Math.max(0, Math.min(pos.getLine(), doc.getLineCount()));
                String lineText = doc.getText(DocumentUtil.getLineTextRange(doc, line));
                String lineTextForPosition = !lineText.isEmpty() ? lineText.substring(0, min(lineText.length(),
                        pos.getCharacter())) : "";
                int tabs = StringUtil.countChars(lineTextForPosition, '\t');
                int tabSize = editor.getSettings().getTabSize(editor.getProject());
                int column = tabs * tabSize + lineTextForPosition.length() - tabs;
                return new LogicalPosition(line, column);
            } catch (IndexOutOfBoundsException e) {
                return null;
            }
        });
    }
}
