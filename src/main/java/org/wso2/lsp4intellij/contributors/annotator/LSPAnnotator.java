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
package org.wso2.lsp4intellij.contributors.annotator;

import com.intellij.lang.annotation.Annotation;
import com.intellij.lang.annotation.AnnotationHolder;
import com.intellij.lang.annotation.ExternalAnnotator;
import com.intellij.lang.annotation.HighlightSeverity;
import com.intellij.openapi.components.ServiceManager;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.util.TextRange;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.psi.PsiFile;
import org.eclipse.lsp4j.Diagnostic;
import org.eclipse.lsp4j.DiagnosticRelatedInformation;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.wso2.lsp4intellij.IntellijLanguageClient;
import org.wso2.lsp4intellij.editor.EditorEventManager;
import org.wso2.lsp4intellij.editor.EditorEventManagerBase;
import org.wso2.lsp4intellij.utils.DocumentUtils;
import org.wso2.lsp4intellij.utils.FileUtils;

import java.util.ArrayList;
import java.util.ConcurrentModificationException;
import java.util.List;

public class LSPAnnotator extends ExternalAnnotator<Object, Object> {

    private static final Logger LOG = Logger.getInstance(LSPAnnotator.class);
    private static final Object RESULT = new Object();

    @Nullable
    @Override
    public Object collectInformation(@NotNull PsiFile file, @NotNull Editor editor, boolean hasErrors) {

        try {
            VirtualFile virtualFile = file.getVirtualFile();

            // If the file is not supported, we skip the annotation by returning null.
            if (!FileUtils.isFileSupported(virtualFile) || !ServiceManager.getService(IntellijLanguageClient.class).isExtensionSupported(virtualFile)) {
                return null;
            }
            String uri = FileUtils.VFSToURI(virtualFile);
            EditorEventManager eventManager = EditorEventManagerBase.forUri(uri);

            // If the diagnostics list is locked, we need to skip annotating the file.
            if (eventManager == null || !(eventManager.isDiagnosticSyncRequired() || eventManager.isCodeActionSyncRequired())) {
                return null;
            }
            return RESULT;
        } catch (Exception e) {
            return null;
        }
    }

    @Nullable
    @Override
    public Object doAnnotate(Object collectedInfo) {
        return RESULT;
    }

    @Override
    public void apply(@NotNull PsiFile file, Object annotationResult, @NotNull AnnotationHolder holder) {

        VirtualFile virtualFile = file.getVirtualFile();
        if (FileUtils.isFileSupported(virtualFile) && ServiceManager.getService(IntellijLanguageClient.class).isExtensionSupported(virtualFile)) {
            String uri = FileUtils.VFSToURI(virtualFile);
            EditorEventManager eventManager = EditorEventManagerBase.forUri(uri);
            if (eventManager == null) {
                return;
            }

            if (eventManager.isCodeActionSyncRequired()) {
                try {
                    updateAnnotations(holder, eventManager);
                } catch (ConcurrentModificationException e) {
                    // Todo - Add proper fix to handle concurrent modifications gracefully.
                    LOG.warn("Error occurred when updating LSP diagnostics due to concurrent modifications.", e);
                } catch (Throwable t) {
                    LOG.warn("Error occurred when updating LSP diagnostics.", t);
                }
            } else if (eventManager.isDiagnosticSyncRequired()) {
                try {
                    createAnnotations(holder, eventManager);
                } catch (ConcurrentModificationException e) {
                    // Todo - Add proper fix to handle concurrent modifications gracefully.
                    LOG.warn("Error occurred when updating LSP code actions due to concurrent modifications.", e);
                } catch (Throwable t) {
                    LOG.warn("Error occurred when updating LSP code actions.", t);
                }
            }
        }
    }

    private void updateAnnotations(AnnotationHolder holder, EditorEventManager eventManager) {
        final List<Annotation> annotations = eventManager.getAnnotations();
        if (annotations == null) {
            return;
        }
        annotations.forEach(annotation -> {
            // TODO: Use 'newAnnotation'; 'createAnnotation' is deprecated.
            Annotation anon = holder.createAnnotation(annotation.getSeverity(),
                    new TextRange(annotation.getStartOffset(), annotation.getEndOffset()), annotation.getMessage());

            if (annotation.getQuickFixes() == null || annotation.getQuickFixes().isEmpty()) {
                return;
            }
            annotation.getQuickFixes().forEach(quickFixInfo -> anon.registerFix(quickFixInfo.quickFix));
        });
    }

    @SuppressWarnings("ResultOfMethodCallIgnored")
    @Nullable
    protected Annotation createAnnotation(Editor editor, AnnotationHolder holder, Diagnostic diagnostic) {
        final int start = DocumentUtils.LSPPosToOffset(editor, diagnostic.getRange().getStart());
        final int end = DocumentUtils.LSPPosToOffset(editor, diagnostic.getRange().getEnd());
        if (start >= end) {
            return null;
        }
        final TextRange textRange = new TextRange(start, end);

        final HighlightSeverity severity;
        Annotation annotation;
        switch (diagnostic.getSeverity()) {
            // TODO: Use 'newAnnotation'; 'create*Annotation' methods are deprecated.
            case Error:
                severity = HighlightSeverity.ERROR;
                annotation = holder.createErrorAnnotation(textRange, diagnostic.getMessage());
                break;
            case Warning:
                severity = HighlightSeverity.WARNING;
                annotation = holder.createWarningAnnotation(textRange, diagnostic.getMessage());
                break;
            case Information:
                severity = HighlightSeverity.INFORMATION;
                annotation = holder.createInfoAnnotation(textRange, diagnostic.getMessage());
                break;
            default:
                severity = HighlightSeverity.WEAK_WARNING;
                annotation = holder.createWeakWarningAnnotation(textRange, diagnostic.getMessage());
        }

        /*
        AnnotationBuilder annotationBuilder = holder.newAnnotation(severity, diagnostic.getMessage());
        annotationBuilder.range(textRange);
*/

        annotation.setTooltip(buildTooltipHtml(diagnostic));

        if (diagnostic.getRelatedInformation() != null && !diagnostic.getRelatedInformation().isEmpty()) {
            annotation.registerFix(new ShowRelatedInformationPopupAction(diagnostic));
        }
        return annotation;
    }

    @NotNull
    private String buildTooltipHtml(Diagnostic diagnostic) {
        StringBuilder sb = new StringBuilder();
        sb.append("<html><div style='margin:5px 0;'>").
                append(diagnostic.getMessage().replaceAll("\n", "<br>")).
                append("</div>");

        // FIXME: escape html from external input before it goes into tooltip!
        if (diagnostic.getRelatedInformation() != null && !diagnostic.getRelatedInformation().isEmpty()) {

            for (DiagnosticRelatedInformation relatedInformation : diagnostic.getRelatedInformation()) {
                final VirtualFile hrefVf = FileUtils.virtualFileFromURI(relatedInformation.getLocation().getUri());
                if(hrefVf != null) {
                    sb.append("<a href=\"#navigation/").append(hrefVf.toNioPath()).
                            append(":").append(relatedInformation.getLocation().getRange().getStart().getLine()).append("\">").
                            append(FileUtils.shortenFileUri(relatedInformation.getLocation().getUri())).append(FileUtils.positionToString(relatedInformation.getLocation().getRange().getStart())).append("</a> ");
                }else{
                    sb.append("<span color='GRAY'>").append(FileUtils.shortenFileUri(relatedInformation.getLocation().getUri())).append(FileUtils.positionToString(relatedInformation.getLocation().getRange().getStart())).append("</span> ");
                }
                sb.append(" ").append(relatedInformation.getMessage()).append("<br>");
            }
        }

        String code = "";
        boolean hasCode = false, hasSource = false;
        if (diagnostic.getCode() != null) {
            code = diagnostic.getCode().toString();
            if (!code.isEmpty()) {
                hasCode = true;
            }
        }
        final String source = diagnostic.getSource();
        if (source != null && !source.isEmpty()) {
            hasSource = true;
        }
        if( hasCode || hasSource) {
            sb.append("<div style='color:GRAY;text-align:right;'>");
            if (hasCode) {
                sb.append(code).append(" ");
            }
            if (hasSource) {
                sb.append(source);
            }
            sb.append("</div>");
        }
        sb.append("</html>");
        return sb.toString();
    }


    private void createAnnotations(AnnotationHolder holder, EditorEventManager eventManager) {
        final List<Diagnostic> diagnostics = eventManager.getDiagnostics();
        final Editor editor = eventManager.editor;

        List<Annotation> annotations = new ArrayList<>();
        diagnostics.forEach(d -> {
            Annotation annotation = createAnnotation(editor, holder, d);
            if (annotation != null) {
                annotations.add(annotation);
            }
        });

        eventManager.setAnnotations(annotations);
        eventManager.setAnonHolder(holder);
    }

}
