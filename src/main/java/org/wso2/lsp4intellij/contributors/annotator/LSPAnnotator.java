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

import com.intellij.analysis.problemsView.toolWindow.ProblemsView;
import com.intellij.codeInsight.intention.IntentionAction;
import com.intellij.codeInspection.util.IntentionFamilyName;
import com.intellij.codeInspection.util.IntentionName;
import com.intellij.icons.AllIcons;
import com.intellij.ide.DataManager;
import com.intellij.lang.annotation.Annotation;
import com.intellij.lang.annotation.AnnotationHolder;
import com.intellij.lang.annotation.ExternalAnnotator;
import com.intellij.lang.annotation.HighlightSeverity;
import com.intellij.openapi.actionSystem.*;
import com.intellij.openapi.components.ServiceManager;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.editor.LogicalPosition;
import com.intellij.openapi.editor.ScrollType;
import com.intellij.openapi.editor.ScrollingModel;
import com.intellij.openapi.editor.markup.*;
import com.intellij.openapi.fileEditor.FileEditorManager;
import com.intellij.openapi.fileEditor.OpenFileDescriptor;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.popup.JBPopupFactory;
import com.intellij.openapi.ui.popup.JBPopupListener;
import com.intellij.openapi.ui.popup.LightweightWindowEvent;
import com.intellij.openapi.ui.popup.ListPopup;
import com.intellij.openapi.util.Iconable;
import com.intellij.openapi.util.TextRange;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.psi.PsiFile;
import com.intellij.ui.JBColor;
import com.intellij.ui.awt.RelativePoint;
import com.intellij.ui.components.JBList;
import com.intellij.util.IncorrectOperationException;
import org.eclipse.lsp4j.*;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.wso2.lsp4intellij.IntellijLanguageClient;
import org.wso2.lsp4intellij.editor.EditorEventManager;
import org.wso2.lsp4intellij.editor.EditorEventManagerBase;
import org.wso2.lsp4intellij.utils.DocumentUtils;
import org.wso2.lsp4intellij.utils.FileUtils;

import javax.swing.*;
import java.awt.*;
import java.util.ArrayList;
import java.util.ConcurrentModificationException;
import java.util.List;

public class LSPAnnotator extends ExternalAnnotator<Object, Object> {

    private static final Logger LOG = Logger.getInstance(LSPAnnotator.class);
    private static final Object RESULT = new Object();
    private ProblemsView problemView;

    @Nullable
    @Override
    public Object collectInformation(@NotNull PsiFile file, @NotNull Editor editor, boolean hasErrors) {

        try {
            VirtualFile virtualFile = file.getVirtualFile();

            // If the file is not supported, we skips the annotation by returning null.
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

        if (diagnostic.getRelatedInformation() != null && !diagnostic.getRelatedInformation().isEmpty()) {
            StringBuilder sb = new StringBuilder();
            sb.append("<html>");
            sb.append(diagnostic.getMessage()).append("<br>");
            final String source = diagnostic.getSource();
            if (source != null && !source.isEmpty()) {
                sb.append("<span color='GREY'>").append(source).append("</span><br>");
            }
            for (DiagnosticRelatedInformation relatedInformation : diagnostic.getRelatedInformation()) {

                sb.append("<a href=\"").append(relatedInformation.getLocation().getUri()).
                        append(":").append(relatedInformation.getLocation().getRange().getStart()).append("\">").
                        append(FileUtils.shortenFileUri(relatedInformation.getLocation().getUri())).append(positionToString(relatedInformation.getLocation().getRange().getStart())).append("</a> ").
                        append(relatedInformation.getMessage()).append("<br>");
            }
            sb.append("</html>");
            annotation.setTooltip(sb.toString());

            // TODO: related information into problemgroup?
            //annotation.setProblemGroup();
            //annotationBuilder.tooltip( sb.toString() );
        }

        if (diagnostic.getRelatedInformation() != null && !diagnostic.getRelatedInformation().isEmpty()) {
            annotation.registerFix(new ShowRelatedInformationAction(diagnostic));
        }
        return annotation;
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

    static String positionToString(Position pos) {
        StringBuilder sb = new StringBuilder();
        sb.append("(").append(pos.getLine()).append(",").append(pos.getCharacter()).append(")");
        return sb.toString();
    }

    private static class InformationItem extends AnAction {
        @NotNull Location loc;

        /**
         * to display the origin item
         */
        InformationItem(@NotNull String uri, @NotNull Diagnostic diag) {
            super("Origin", "Links to origin: " + FileUtils.shortenFileUri(uri) + " " + positionToString(diag.getRange().getStart()), null);
            loc = new Location(uri, diag.getRange());
        }

        InformationItem(@NotNull DiagnosticRelatedInformation relatedInformation) {
            super(relatedInformation.getMessage(), "Links to related Information: " + FileUtils.shortenFileUri(relatedInformation.getLocation().getUri()) + " " + positionToString(relatedInformation.getLocation().getRange().getStart()), null);
            loc = relatedInformation.getLocation();
        }

        @Override
        public void actionPerformed(@NotNull AnActionEvent e) {
        }
    }

    private static class ShowRelatedInformationAction implements IntentionAction, Iconable {

        private final Diagnostic diagnostic;
        private RangeHighlighter highlighter = null;

        public ShowRelatedInformationAction(Diagnostic diagnostic) {
            this.diagnostic = diagnostic;
        }

        @Override
        public @NotNull @IntentionName String getText() {
            return "Find cause";
        }

        @Override
        public @NotNull @IntentionFamilyName String getFamilyName() {
            return "Lsp Find cause";
        }

        @Override
        public boolean isAvailable(@NotNull Project project, Editor editor, PsiFile file) {
            return true;
        }

        @Override
        public void invoke(@NotNull Project project, Editor editor, PsiFile file) throws IncorrectOperationException {

            List<AnAction> actions = new ArrayList<>();
            final String uri = FileUtils.VFSToURI(file.getVirtualFile());
            if (uri == null) {
                return;
            }
            actions.add(new InformationItem(uri, diagnostic));
            actions.add(Separator.create());

            for (DiagnosticRelatedInformation relatedInformation : diagnostic.getRelatedInformation()) {
                actions.add(new InformationItem(relatedInformation));
            }

            String title = "Related Information";
            DataContext context = DataManager.getInstance().getDataContext(editor.getComponent());
            DefaultActionGroup group = new DefaultActionGroup(actions);

            ListPopup popup = JBPopupFactory.getInstance()
                    .createActionGroupPopup(title, group, context, JBPopupFactory.ActionSelectionAid.MNEMONICS, true);

            popup.addListener(new JBPopupListener() {
                @Override
                public void onClosed(@NotNull LightweightWindowEvent event) {
                    if (highlighter != null) {
                        editor.getMarkupModel().removeHighlighter(highlighter);
                        highlighter = null;
                    }
                }
            });

            popup.addListSelectionListener(event -> {
                // preview selection
                @SuppressWarnings("rawtypes")
                JBList list = (JBList) event.getSource();

                final VirtualFile vf;
                final Range range;
                if (list.getSelectedIndex() == 0) {
                    range = diagnostic.getRange();
                    vf = file.getVirtualFile();
                } else {
                    final DiagnosticRelatedInformation diagnosticRelatedInformation = diagnostic.getRelatedInformation().get(list.getSelectedIndex() - 1);
                    range = diagnosticRelatedInformation.getLocation().getRange();
                    vf = FileUtils.virtualFileFromURI(diagnosticRelatedInformation.getLocation().getUri());
                }

                if (vf == null) {
                    return;
                }
                // focus editor respective to selected related informations uri
                Editor previewEditor = FileEditorManager.getInstance(project).openTextEditor(new OpenFileDescriptor(project, vf), true);
                if (previewEditor == null) {
                    return;
                }

                final ScrollingModel scrollingModel = previewEditor.getScrollingModel();
                scrollingModel.scrollHorizontally(range.getStart().getLine());
                scrollingModel.scrollTo(new LogicalPosition(range.getStart().getLine(), range.getStart().getCharacter()), ScrollType.CENTER_UP);

                // show hint on that location
                MarkupModel model = editor.getMarkupModel();
                int from = editor.logicalPositionToOffset(new LogicalPosition(range.getStart().getLine(), range.getStart().getCharacter()));
                int to = editor.logicalPositionToOffset(new LogicalPosition(range.getEnd().getLine(), range.getEnd().getCharacter()));
                // detect: until end of line -> dont wrap to "before first character"
                to = range.getEnd().getCharacter() == 0 ? to - 1 : to;

                if (highlighter != null) {
                    model.removeHighlighter(highlighter);
                    highlighter.dispose();
                }

                // TODO: change to TextAttributesKey to allow for custom higlightcolor
                highlighter = model.addRangeHighlighter(from, to, HighlighterLayer.SELECTION, new TextAttributes(JBColor.BLACK, JBColor.LIGHT_GRAY, null, EffectType.BOXED, 0), HighlighterTargetArea.EXACT_RANGE);

            });

            final Point aPointOnComponent = editor.visualPositionToXY(editor.getCaretModel().getVisualPosition());
            aPointOnComponent.y += (editor.getLineHeight() + 2);
            // TODO: handle overlapping / hiding the highlight with popup
            popup.show(new RelativePoint(editor.getComponent(), aPointOnComponent));

        }

        @Override
        public boolean startInWriteAction() {
            return false;
        }

        @Override
        public Icon getIcon(int flags) {
            return AllIcons.Actions.IntentionBulb;
        }
    }
}
