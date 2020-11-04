package org.wso2.lsp4intellij.contributors.symbol;

import com.intellij.ide.projectView.PresentationData;
import com.intellij.ide.structureView.*;
import com.intellij.ide.util.treeView.smartTree.Grouper;
import com.intellij.ide.util.treeView.smartTree.SortableTreeElement;
import com.intellij.ide.util.treeView.smartTree.Sorter;
import com.intellij.ide.util.treeView.smartTree.TreeElement;
import com.intellij.lang.PsiStructureViewFactory;
import com.intellij.navigation.ItemPresentation;
import com.intellij.openapi.components.ServiceManager;
import com.intellij.openapi.editor.Editor;
import com.intellij.psi.NavigatablePsiElement;
import com.intellij.psi.PsiFile;
import com.intellij.psi.PsiPlainTextFile;
import org.eclipse.lsp4j.DocumentSymbol;
import org.eclipse.lsp4j.DocumentSymbolParams;
import org.eclipse.lsp4j.SymbolInformation;
import org.eclipse.lsp4j.TextDocumentIdentifier;
import org.eclipse.lsp4j.jsonrpc.messages.Either;
import org.jetbrains.annotations.NotNull;
import org.wso2.lsp4intellij.IntellijLanguageClient;
import org.wso2.lsp4intellij.client.languageserver.wrapper.LanguageServerWrapper;
import org.wso2.lsp4intellij.contributors.psi.LSPPsiSymbol;
import org.wso2.lsp4intellij.requests.Timeout;
import org.wso2.lsp4intellij.requests.Timeouts;
import org.wso2.lsp4intellij.utils.ApplicationUtils;
import org.wso2.lsp4intellij.utils.DocumentUtils;
import org.wso2.lsp4intellij.utils.FileUtils;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

public final class LSPStructureViewFactory implements PsiStructureViewFactory {

  List<TreeElement> treeElements = new ArrayList<>();

  @Nullable
  @Override
  public StructureViewBuilder getStructureViewBuilder(@NotNull final PsiFile psiFile) {
    return new TreeBasedStructureViewBuilder() {
      @NotNull
      @Override
      public StructureViewModel createStructureViewModel(@Nullable Editor editor) {

        final LSPStructureViewModel lspStructureViewModel = new LSPStructureViewModel(psiFile);
        treeElements.clear();

        ApplicationUtils.invokeLater(() -> {
          // load data from server
          final Set<LanguageServerWrapper> wrappers = ServiceManager.getService(IntellijLanguageClient.class).getAllServerWrappersFor(FileUtils.projectToUri(psiFile.getProject()));
          final Optional<LanguageServerWrapper> wrapperOpt = wrappers.stream().findFirst();
          if(wrapperOpt.isPresent()) {
            final LanguageServerWrapper wrapper = wrapperOpt.get();
            final CompletableFuture<List<Either<SymbolInformation, DocumentSymbol>>> listCompletableFuture = wrapper.getRequestManager().documentSymbol(new DocumentSymbolParams(new TextDocumentIdentifier(FileUtils.uriFromVirtualFile(psiFile.getVirtualFile()))));

            List<Either<SymbolInformation, DocumentSymbol>> eithers;
            try {
              eithers = listCompletableFuture.get(Timeout.getTimeout(Timeouts.SYMBOLS), TimeUnit.MILLISECONDS);
              wrapper.notifySuccess(Timeouts.SYMBOLS);

              if(eithers != null) {
                for (Either<SymbolInformation, DocumentSymbol> either : eithers) {
                  if (either.isLeft()) {
                    final SymbolInformation symbolInfo = either.getLeft();
                    treeElements.add(new LSPStructureViewFactory.LSPStructureViewElement(new LSPPsiSymbol(symbolInfo.getKind(), symbolInfo.getName(), psiFile.getProject(), DocumentUtils.LSPPosToOffset(editor, symbolInfo.getLocation().getRange().getStart()), DocumentUtils.LSPPosToOffset(editor, symbolInfo.getLocation().getRange().getEnd()), psiFile.getContainingFile())));
                  } else if (either.isRight()) {
                    final DocumentSymbol docSymbol = either.getRight();
                    treeElements.add(new LSPStructureViewFactory.LSPStructureViewElement(
                            new LSPPsiSymbol(docSymbol.getKind(), docSymbol.getName(), psiFile.getProject(), DocumentUtils.LSPPosToOffset(editor, docSymbol.getRange().getStart()), DocumentUtils.LSPPosToOffset(editor, docSymbol.getRange().getEnd()), psiFile.getContainingFile())));
                  }
                }
              }

              lspStructureViewModel.fireModelUpdate();

            }catch (InterruptedException | ExecutionException | TimeoutException e) {
              wrapper.notifyFailure(Timeouts.SYMBOLS);
              e.printStackTrace();
            }
          }
        });

        return lspStructureViewModel;
      }
    };
  }

  class LSPStructureViewElement implements StructureViewTreeElement, SortableTreeElement {

    private final NavigatablePsiElement navigatablePsiElement;

    public LSPStructureViewElement(NavigatablePsiElement element) {
      this.navigatablePsiElement = element;
    }

    @Override
    public Object getValue() {
      return navigatablePsiElement;
    }

    @Override
    public void navigate(boolean requestFocus) {
      navigatablePsiElement.navigate(requestFocus);
    }

    @Override
    public boolean canNavigate() {
      return navigatablePsiElement.canNavigate();
    }

    @Override
    public boolean canNavigateToSource() {
      return navigatablePsiElement.canNavigateToSource();
    }

    @NotNull
    @Override
    public String getAlphaSortKey() {
      String name = navigatablePsiElement.getName();
      return name != null ? name : "";
    }

    @NotNull
    @Override
    public ItemPresentation getPresentation() {
      ItemPresentation presentation = navigatablePsiElement.getPresentation();
      return presentation != null ? presentation : new PresentationData();
    }

    @NotNull
    @Override
    public TreeElement[] getChildren() {
      if( navigatablePsiElement instanceof PsiPlainTextFile)
      {
        return treeElements.toArray(new TreeElement[0]);
      }
      return EMPTY_ARRAY;
    }

  }

  class LSPStructureViewModel extends StructureViewModelBase implements
          StructureViewModel.ElementInfoProvider {

    public LSPStructureViewModel(PsiFile psiFile) {
      super(psiFile, new LSPStructureViewFactory.LSPStructureViewElement(psiFile));
    }

    @Override
    public @NotNull Grouper[] getGroupers() {
      // TODO: create some SymbolKindGrouper
      return super.getGroupers();
    }

    @NotNull
    public Sorter[] getSorters() {
      return new Sorter[]{Sorter.ALPHA_SORTER};
    }

    @Override
    public boolean isAlwaysShowsPlus(StructureViewTreeElement element) {
      return false;
    }

    @Override
    public boolean isAlwaysLeaf(StructureViewTreeElement element) {
      return false; //element instanceof PlainTextLanguage;
    }

  }


}
