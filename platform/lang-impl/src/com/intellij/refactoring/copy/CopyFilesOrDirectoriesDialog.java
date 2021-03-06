/*
 * Copyright 2000-2009 JetBrains s.r.o.
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

package com.intellij.refactoring.copy;

import com.intellij.ide.util.DirectoryUtil;
import com.intellij.openapi.actionSystem.ActionManager;
import com.intellij.openapi.actionSystem.IdeActions;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.command.CommandProcessor;
import com.intellij.openapi.fileChooser.FileChooserDescriptor;
import com.intellij.openapi.fileChooser.FileChooserDescriptorFactory;
import com.intellij.openapi.help.HelpManager;
import com.intellij.openapi.keymap.KeymapUtil;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.DialogWrapper;
import com.intellij.openapi.ui.Messages;
import com.intellij.openapi.ui.TextComponentAccessor;
import com.intellij.openapi.ui.impl.DialogWrapperPeerImpl;
import com.intellij.psi.PsiDirectory;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiFile;
import com.intellij.psi.PsiManager;
import com.intellij.refactoring.RefactoringBundle;
import com.intellij.ui.DocumentAdapter;
import com.intellij.ui.RecentsManager;
import com.intellij.ui.TextFieldWithHistoryWithBrowseButton;
import com.intellij.ui.components.JBLabelDecorator;
import com.intellij.util.IncorrectOperationException;
import com.intellij.util.ui.FormBuilder;
import com.intellij.util.ui.UIUtil;
import org.jetbrains.annotations.NonNls;

import javax.swing.*;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import java.awt.*;
import java.io.File;
import java.util.List;

public class CopyFilesOrDirectoriesDialog extends DialogWrapper {
  private JLabel myInformationLabel;
  private TextFieldWithHistoryWithBrowseButton myTargetDirectoryField;
  private JTextField myNewNameField;
  private final Project myProject;
  private final boolean myShowDirectoryField;
  private final boolean myShowNewNameField;

  private PsiDirectory myTargetDirectory;
  @NonNls private static final String RECENT_KEYS = "CopyFile.RECENT_KEYS";

  public CopyFilesOrDirectoriesDialog(PsiElement[] elements, PsiDirectory defaultTargetDirectory, Project project, boolean doClone) {
    super(project, true);
    myProject = project;
    myShowDirectoryField = !doClone;
    myShowNewNameField = elements.length == 1;

    if (doClone && elements.length != 1) {
      throw new IllegalArgumentException("wrong number of elements to clone: " + elements.length);
    }

    setTitle(doClone ?
             RefactoringBundle.message("copy.files.clone.title") :
             RefactoringBundle.message("copy.files.copy.title"));
    init();

    if (elements.length == 1) {
      String text;
      if (elements[0] instanceof PsiFile) {
        PsiFile file = (PsiFile)elements[0];
        text = doClone ?
               RefactoringBundle.message("copy.files.clone.file.0", file.getVirtualFile().getPresentableUrl()) :
               RefactoringBundle.message("copy.files.copy.file.0", file.getVirtualFile().getPresentableUrl());
        final String fileName = file.getName();
        myNewNameField.setText(fileName);
        final int dotIdx = fileName.lastIndexOf(".");
        if (dotIdx > -1) {
          myNewNameField.select(0, dotIdx);
          myNewNameField.putClientProperty(DialogWrapperPeerImpl.HAVE_INITIAL_SELECTION, true);
        }
      }
      else {
        PsiDirectory directory = (PsiDirectory)elements[0];
        text = doClone ?
               RefactoringBundle.message("copy.files.clone.directory.0", directory.getVirtualFile().getPresentableUrl()) :
               RefactoringBundle.message("copy.files.copy.directory.0", directory.getVirtualFile().getPresentableUrl());
        myNewNameField.setText(directory.getName());
      }
      myInformationLabel.setText(text);
    }
    else {
      setMultipleElementCopyLabel(elements);
    }

    if (myShowDirectoryField) {
      myTargetDirectoryField.getChildComponent()
        .setText(defaultTargetDirectory == null ? "" : defaultTargetDirectory.getVirtualFile().getPresentableUrl());
    }
    validateOKButton();
  }

  private void setMultipleElementCopyLabel(PsiElement[] elements) {
    boolean allFiles = true;
    boolean allDirectories = true;
    for (PsiElement element : elements) {
      if (element instanceof PsiDirectory) {
        allFiles = false;
      }
      else {
        allDirectories = false;
      }
    }
    if (allFiles) {
      myInformationLabel.setText(RefactoringBundle.message("copy.files.copy.specified.files.label"));
    }
    else if (allDirectories) {
      myInformationLabel.setText(RefactoringBundle.message("copy.files.copy.specified.directories.label"));
    }
    else {
      myInformationLabel.setText(RefactoringBundle.message("copy.files.copy.specified.mixed.label"));
    }
  }

  protected Action[] createActions() {
    return new Action[]{getOKAction(), getCancelAction(), getHelpAction()};
  }

  public JComponent getPreferredFocusedComponent() {
    return myShowNewNameField ? myNewNameField : myTargetDirectoryField.getChildComponent();
  }

  protected JComponent createCenterPanel() {
    return new JPanel(new BorderLayout());
  }

  protected JComponent createNorthPanel() {
    myInformationLabel = JBLabelDecorator.createJBLabelDecorator().setBold(true);
    final FormBuilder formBuilder = FormBuilder.createFormBuilder().addComponent(myInformationLabel).addVerticalGap(
      UIUtil.LARGE_VGAP - UIUtil.DEFAULT_VGAP);
    DocumentListener documentListener = new DocumentAdapter() {
      public void textChanged(DocumentEvent event) {
        validateOKButton();
      }
    };

    if (myShowNewNameField) {
      myNewNameField = new JTextField(60);
      myNewNameField.getDocument().addDocumentListener(documentListener);
      formBuilder.addLabeledComponent(RefactoringBundle.message("copy.files.new.name.label"), myNewNameField);
    }

    if (myShowDirectoryField) {
      myTargetDirectoryField = new TextFieldWithHistoryWithBrowseButton();
      final List<String> recentEntries = RecentsManager.getInstance(myProject).getRecentEntries(RECENT_KEYS);
      if (recentEntries != null) {
        myTargetDirectoryField.getChildComponent().setHistory(recentEntries);
      }
      final FileChooserDescriptor descriptor = FileChooserDescriptorFactory.createSingleFolderDescriptor();
      myTargetDirectoryField.addBrowseFolderListener(RefactoringBundle.message("select.target.directory"),
                                                     RefactoringBundle.message("the.file.will.be.copied.to.this.directory"),
                                                     myProject, descriptor,
                                                     TextComponentAccessor.TEXT_FIELD_WITH_HISTORY_WHOLE_TEXT);
      myTargetDirectoryField.setTextFieldPreferredWidth(60);
      myTargetDirectoryField.getChildComponent().addDocumentListener(new DocumentAdapter() {
        @Override
        protected void textChanged(DocumentEvent e) {
          validateOKButton();
        }
      });
      formBuilder.addLabeledComponent(RefactoringBundle.message("copy.files.to.directory.label"), myTargetDirectoryField);

      String shortcutText =
        KeymapUtil.getFirstKeyboardShortcutText(ActionManager.getInstance().getAction(IdeActions.ACTION_CODE_COMPLETION));
      formBuilder.addTooltip(RefactoringBundle.message("path.completion.shortcut", shortcutText));
    }

    return formBuilder.getPanel();
  }

  public PsiDirectory getTargetDirectory() {
    return myTargetDirectory;
  }

  public String getNewName() {
    return myNewNameField != null ? myNewNameField.getText().trim() : null;
  }

  protected void doOKAction() {
    if (myShowNewNameField) {
      String newName = getNewName();

      if (newName.length() == 0) {
        Messages.showMessageDialog(myProject, RefactoringBundle.message("no.new.name.specified"), RefactoringBundle.message("error.title"),
                                   Messages.getErrorIcon());
        return;
      }
    }

    if (myShowDirectoryField) {
      final String targetDirectoryName = myTargetDirectoryField.getChildComponent().getText();

      if (targetDirectoryName.length() == 0) {
        Messages.showMessageDialog(myProject, RefactoringBundle.message("no.target.directory.specified"),
                                   RefactoringBundle.message("error.title"), Messages.getErrorIcon());
        return;
      }

      RecentsManager.getInstance(myProject).registerRecentEntry(RECENT_KEYS, targetDirectoryName);

      CommandProcessor.getInstance().executeCommand(myProject, new Runnable() {
        public void run() {
          ApplicationManager.getApplication().runWriteAction(new Runnable() {
            public void run() {
              try {
                myTargetDirectory =
                  DirectoryUtil.mkdirs(PsiManager.getInstance(myProject), targetDirectoryName.replace(File.separatorChar, '/'));
              }
              catch (IncorrectOperationException e) {
              }
            }
          });
        }
      }, RefactoringBundle.message("create.directory"), null);

      if (myTargetDirectory == null) {
        Messages
          .showMessageDialog(myProject, RefactoringBundle.message("cannot.create.directory"), RefactoringBundle.message("error.title"),
                             Messages.getErrorIcon());
        return;
      }
    }

    super.doOKAction();
  }

  private void validateOKButton() {
    if (myShowDirectoryField) {
      if (myTargetDirectoryField.getChildComponent().getText().length() == 0) {
        setOKActionEnabled(false);
        return;
      }
    }
    if (myShowNewNameField) {
      if (getNewName().length() == 0) {
        setOKActionEnabled(false);
        return;
      }
    }
    setOKActionEnabled(true);
  }

  protected void doHelpAction() {
    HelpManager.getInstance().invokeHelp("refactoring.copyClass");
  }
}
