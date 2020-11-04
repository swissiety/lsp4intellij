package org.wso2.lsp4intellij.contributors.symbol;

import com.intellij.codeInsight.daemon.RelatedItemLineMarkerInfo;
import com.intellij.codeInsight.daemon.RelatedItemLineMarkerProvider;
import com.intellij.codeInsight.navigation.NavigationGutterIconBuilder;
import com.intellij.icons.AllIcons;
import com.intellij.openapi.components.ServiceManager;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.editor.Document;
import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.editor.EditorFactory;
import com.intellij.openapi.fileEditor.FileDocumentManager;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.util.TextRange;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiFile;
import com.intellij.psi.PsiManager;
import org.eclipse.lsp4j.*;
import org.eclipse.lsp4j.jsonrpc.messages.Either;
import org.jetbrains.annotations.NotNull;
import org.wso2.lsp4intellij.IntellijLanguageClient;
import org.wso2.lsp4intellij.client.languageserver.requestmanager.RequestManager;
import org.wso2.lsp4intellij.client.languageserver.wrapper.LanguageServerWrapper;
import org.wso2.lsp4intellij.contributors.psi.LSPPsiElement;
import org.wso2.lsp4intellij.contributors.psi.LSPPsiSymbol;
import org.wso2.lsp4intellij.requests.Timeout;
import org.wso2.lsp4intellij.requests.Timeouts;
import org.wso2.lsp4intellij.utils.DocumentUtils;
import org.wso2.lsp4intellij.utils.FileUtils;

import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;


// TODO: [ms] refresh if the file is modified
// FIXME: [ms] BUG: adds multiple targets?!
public class LineMarkerProvider extends RelatedItemLineMarkerProvider {

  /*  use those standard icons
  // [ms] in future lsp versions: implement more according to infos from typehierarchy
  AllIcons.Gutter.OverridenMethod
  AllIcons.Gutter.ImplementedMethod
  AllIcons.Gutter.OverridingMethod
  AllIcons.Gutter.ImplementingMethod
  AllIcons.Gutter.SiblingInheritedMethod (overridden+overriding combined)
  */

  private static Logger LOG = Logger.getInstance(LineMarkerProvider.class);


  @Override
  protected void collectNavigationMarkers(@NotNull PsiElement element,
                                          @NotNull Collection<? super RelatedItemLineMarkerInfo<?>> result) {

    // https://jetbrains.org/intellij/sdk/docs/tutorials/custom_language_support/line_marker_provider.html
    // markers are collected in two phases
    // first: collect for psi that is visible in editor
    // second: rest of file -> we only support psiFile -> covers whole document -> is everywhere visible -> two times visible
    // -> skip second addition -> no duplicates
    if(!result.isEmpty()){
      return;
    }

    final PsiFile containingFile = element.getContainingFile();
    final VirtualFile virtualFile = containingFile.getVirtualFile();

    if(!FileUtils.isFileSupported(virtualFile)){
      return;
    }

    // load data from server
    final Set<LanguageServerWrapper> wrappers = ServiceManager.getService(IntellijLanguageClient.class).getAllServerWrappersFor(FileUtils.projectToUri(element.getProject()));
    final Optional<LanguageServerWrapper> wrapperOpt = wrappers.stream().findFirst();
    if (!wrapperOpt.isPresent()) {
      return;
    }
    final LanguageServerWrapper wrapper = wrapperOpt.get();
    final TextDocumentIdentifier textDocument = new TextDocumentIdentifier(FileUtils.uriFromVirtualFile(virtualFile));
    final RequestManager requestManager = wrapper.getRequestManager();
    if(requestManager == null){
      return;   // not connected
    }
    final CompletableFuture<List<Either<SymbolInformation, DocumentSymbol>>> listCompletableFuture = requestManager.documentSymbol(new DocumentSymbolParams(textDocument));

    List<Either<SymbolInformation, DocumentSymbol>> eithers;
    try {
      eithers = listCompletableFuture.get(Timeout.getTimeout(Timeouts.SYMBOLS), TimeUnit.MILLISECONDS);
      wrapper.notifySuccess(Timeouts.SYMBOLS);
    }catch (InterruptedException | ExecutionException | TimeoutException e) {
      wrapper.notifyFailure(Timeouts.SYMBOLS);
      e.printStackTrace();
      return;
    }

    if(eithers == null){
      return;
    }

    final Document document = FileDocumentManager.getInstance().getDocument(virtualFile);
    if (document == null){
      return;
    }
    final Optional<@NotNull Editor> foundEditor = Arrays.stream(EditorFactory.getInstance().getEditors(document, element.getProject())).findFirst();
    if (!foundEditor.isPresent()) {
      return;
    }
    Editor editor = foundEditor.get();

    final Project project = element.getProject();
    for (Either<SymbolInformation, DocumentSymbol> either : eithers) {
      LSPPsiSymbol lspPsiSymbol;
      final Position startPos;
      if (either.isLeft()) {
        final SymbolInformation symbolInfo = either.getLeft();
        if (symbolInfo.getKind() != SymbolKind.Method && symbolInfo.getKind() != SymbolKind.Constructor && symbolInfo.getKind() != SymbolKind.Class && symbolInfo.getKind() != SymbolKind.Interface) {
          continue;
        }
        startPos = symbolInfo.getLocation().getRange().getStart();
        final int start = DocumentUtils.LSPPosToOffset(editor, startPos);
        final int end = DocumentUtils.LSPPosToOffset(editor, symbolInfo.getLocation().getRange().getEnd());
        if (start < 0 || end < 0) {
          continue;
        }
        lspPsiSymbol = new LSPPsiSymbol(symbolInfo.getKind(), symbolInfo.getName(), project, start, end, containingFile);
      } else if (either.isRight()) {
        final DocumentSymbol docSymbol = either.getRight();
        if (docSymbol.getKind() != SymbolKind.Method && docSymbol.getKind() != SymbolKind.Constructor && docSymbol.getKind() != SymbolKind.Class && docSymbol.getKind() != SymbolKind.Interface) {
          continue;
        }
        startPos = docSymbol.getRange().getStart();
        final int start = DocumentUtils.LSPPosToOffset(editor, startPos);
        final int end = DocumentUtils.LSPPosToOffset(editor, docSymbol.getRange().getEnd());
        if (start < 0 || end < 0) {
          continue;
        }
        lspPsiSymbol = new LSPPsiSymbol(docSymbol.getKind(), docSymbol.getName(), project, start, end, containingFile);
      } else {
        continue;
      }

      // FIXME: set target elements from implementation (in lsp3.16 try it via from typehierarchy and use implementation as fallback)
      int logicalStart = DocumentUtils.LSPPosToOffset(editor, new Position(0, 5));
      int logicalEnd = DocumentUtils.LSPPosToOffset(editor, new Position(0, 12));
      String name = editor.getDocument().getText(new TextRange(logicalStart, logicalEnd));

      if (logicalStart < 0 || logicalEnd < 0) {
        continue;
      }

      // get and apply subtypes
      try {
        final CompletableFuture<Either<List<? extends Location>, List<? extends LocationLink>>> implementation = requestManager.implementation(new ImplementationParams(textDocument, startPos));
        final Either<List<? extends Location>, List<? extends LocationLink>> listEither = implementation.get(Timeout.getTimeout(Timeouts.IMPLEMENTATION), TimeUnit.MILLISECONDS);
        wrapper.notifySuccess(Timeouts.IMPLEMENTATION);

        if (listEither!= null) {

          List<LSPPsiElement> targetElements = new ArrayList<>();

          if (listEither.isLeft()) {
            for (Location location : listEither.getLeft()) {

              int logStart = DocumentUtils.LSPPosToOffset(editor, location.getRange().getStart());
              int logEnd = DocumentUtils.LSPPosToOffset(editor, location.getRange().getEnd());
              String targetname = editor.getDocument().getText(new TextRange(logStart, logEnd));

              final PsiFile file = PsiManager.getInstance(project).findFile(FileUtils.virtualFileFromURI(location.getUri()));
              targetElements.add(new LSPPsiElement(targetname, project, logStart, logEnd, file));

            }
          }else if (listEither.isRight()) {
            for (LocationLink locationLink : listEither.getRight()) {
              // TODO: implement
            }
          }

          if(!targetElements.isEmpty()) {
            NavigationGutterIconBuilder<PsiElement> builder =
                    NavigationGutterIconBuilder.create(AllIcons.Gutter.ImplementedMethod)
                            .setTargets(targetElements)
                            .setTooltipText("Navigate to Overriding Method.");
            final RelatedItemLineMarkerInfo<PsiElement> lineMarkerInfo = builder.createLineMarkerInfo(lspPsiSymbol);
            result.add(lineMarkerInfo);
          }

        }

      } catch (InterruptedException | TimeoutException | ExecutionException e) {
        wrapper.notifyFailure(Timeouts.IMPLEMENTATION);
        e.printStackTrace();
      }

    }

  }

}
