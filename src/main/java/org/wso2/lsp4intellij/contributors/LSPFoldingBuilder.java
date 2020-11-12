package org.wso2.lsp4intellij.contributors;

import com.intellij.lang.ASTNode;
import com.intellij.lang.folding.CustomFoldingBuilder;
import com.intellij.lang.folding.FoldingDescriptor;
import com.intellij.openapi.components.ServiceManager;
import com.intellij.openapi.editor.Document;
import com.intellij.openapi.editor.FoldingGroup;
import com.intellij.openapi.util.TextRange;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiFile;
import org.eclipse.lsp4j.FoldingRange;
import org.eclipse.lsp4j.FoldingRangeRequestParams;
import org.eclipse.lsp4j.Position;
import org.jetbrains.annotations.NotNull;
import org.wso2.lsp4intellij.IntellijLanguageClient;
import org.wso2.lsp4intellij.client.languageserver.wrapper.LanguageServerWrapper;
import org.wso2.lsp4intellij.contributors.psi.LSPPsiElement;
import org.wso2.lsp4intellij.editor.EditorEventManager;
import org.wso2.lsp4intellij.requests.Timeout;
import org.wso2.lsp4intellij.requests.Timeouts;
import org.wso2.lsp4intellij.utils.DocumentUtils;
import org.wso2.lsp4intellij.utils.FileUtils;

import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

public class LSPFoldingBuilder extends CustomFoldingBuilder {

  @Override
  protected void buildLanguageFoldRegions(@NotNull List<FoldingDescriptor> descriptors, @NotNull PsiElement root, @NotNull Document document, boolean quick) {
    final PsiFile containingFile = root.getContainingFile();
    if( containingFile == null){
      return;
    }
    final VirtualFile vf = containingFile.getVirtualFile();
    if (vf== null) {
      return;
    }
    final Set<LanguageServerWrapper> allServerWrappersFor = ServiceManager.getService(IntellijLanguageClient.class).getAllServerWrappersFor(FileUtils.projectToUri(root.getProject()));
    // TODO: [ms] implement for multiple servers
    final Optional<LanguageServerWrapper> any = allServerWrappersFor.stream().findAny();
    if(!any.isPresent()){
      return;
    }
    final LanguageServerWrapper wrapper = any.get();

    final EditorEventManager editorEventManager = wrapper.getConnectedEditors().get(FileUtils.uriFromVirtualFile(vf));
    if(editorEventManager == null){
      return;
    }

    final CompletableFuture<List<FoldingRange>> foldingFuture = editorEventManager.getRequestManager().foldingRange(new FoldingRangeRequestParams(editorEventManager.getIdentifier()));
    if(foldingFuture == null){
      return;
    }

    try {
      List<FoldingRange> foldingRanges = foldingFuture.get(Timeout.getTimeout(Timeouts.FOLDING), TimeUnit.MILLISECONDS);
      wrapper.notifySuccess(Timeouts.FOLDING);

      for (FoldingRange foldingRange : foldingRanges) {

        // FIXME: [ms] here is sth strange/buggy
        int endOffset = DocumentUtils.LSPPosToOffset(editorEventManager.editor, new Position(foldingRange.getStartLine(), foldingRange.getStartCharacter()));
        int startoffset = DocumentUtils.LSPPosToOffset(editorEventManager.editor, new Position(foldingRange.getEndLine(), foldingRange.getEndCharacter()));
        final String text = editorEventManager.editor.getDocument().getText(new TextRange(startoffset, endOffset));
        descriptors.add( new FoldingDescriptor(new LSPPsiElement(text, editorEventManager.getProject(), startoffset, endOffset, root.getContainingFile()), startoffset, endOffset, FoldingGroup.newGroup(foldingRange.getKind()), "..."));
      }


    } catch (InterruptedException | TimeoutException | ExecutionException e) {
      e.printStackTrace();
      wrapper.notifyFailure(Timeouts.FOLDING);
    }

  }

  @Override
  protected String getLanguagePlaceholderText(@NotNull ASTNode node, @NotNull TextRange range) {
    // TODO: [ms] implement/improve dots
    return "...";
  }

  @Override
  protected boolean isRegionCollapsedByDefault(@NotNull ASTNode node) {
    return false;
  }
}
