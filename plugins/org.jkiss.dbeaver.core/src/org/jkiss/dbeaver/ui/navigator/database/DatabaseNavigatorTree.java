/*
 * DBeaver - Universal Database Manager
 * Copyright (C) 2010-2017 Serge Rider (serge@jkiss.org)
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.jkiss.dbeaver.ui.navigator.database;

import org.eclipse.core.runtime.IStatus;
import org.eclipse.core.runtime.Status;
import org.eclipse.core.runtime.jobs.IJobChangeEvent;
import org.eclipse.core.runtime.jobs.JobChangeAdapter;
import org.eclipse.jface.viewers.*;
import org.eclipse.swt.SWT;
import org.eclipse.swt.custom.TreeEditor;
import org.eclipse.swt.events.*;
import org.eclipse.swt.graphics.Point;
import org.eclipse.swt.graphics.Rectangle;
import org.eclipse.swt.layout.FillLayout;
import org.eclipse.swt.widgets.*;
import org.eclipse.ui.IWorkbenchCommandConstants;
import org.eclipse.ui.dialogs.FilteredTree;
import org.eclipse.ui.dialogs.PatternFilter;
import org.eclipse.ui.progress.WorkbenchJob;
import org.jkiss.code.NotNull;
import org.jkiss.dbeaver.DBException;
import org.jkiss.dbeaver.DBeaverPreferences;
import org.jkiss.dbeaver.Log;
import org.jkiss.dbeaver.core.DBeaverCore;
import org.jkiss.dbeaver.core.DBeaverUI;
import org.jkiss.dbeaver.model.navigator.*;
import org.jkiss.dbeaver.model.runtime.DBRProgressMonitor;
import org.jkiss.dbeaver.model.runtime.DBRRunnableWithResult;
import org.jkiss.dbeaver.ui.AbstractUIJob;
import org.jkiss.dbeaver.ui.ActionUtils;
import org.jkiss.dbeaver.ui.actions.navigator.NavigatorHandlerObjectRename;
import org.jkiss.dbeaver.ui.navigator.NavigatorUtils;
import org.jkiss.utils.ArrayUtils;
import org.jkiss.utils.CommonUtils;

import java.lang.reflect.InvocationTargetException;

public class DatabaseNavigatorTree extends Composite implements INavigatorListener
{
    private static final Log log = Log.getLog(DatabaseNavigatorTree.class);

    private TreeViewer treeViewer;
    private DBNModel model;
    private TreeEditor treeEditor;
    private boolean checkEnabled;
    private ISelection defaultSelection;
    private volatile boolean isFiltering = false;

    public DatabaseNavigatorTree(Composite parent, DBNNode rootNode, int style)
    {
        this(parent, rootNode, style, false);
    }

    public DatabaseNavigatorTree(Composite parent, DBNNode rootNode, int style, boolean showRoot)
    {
        super(parent, SWT.NONE);
        this.setLayout(new FillLayout());
        this.defaultSelection = new StructuredSelection(rootNode);
        this.model = DBeaverCore.getInstance().getNavigatorModel();
        this.model.addListener(this);
        addDisposeListener(new DisposeListener() {
            @Override
            public void widgetDisposed(DisposeEvent e)
            {
                if (model != null) {
                    model.removeListener(DatabaseNavigatorTree.this);
                    model = null;
                }
            }
        });

        treeViewer = doCreateTreeViewer(this, style);

        treeViewer.getTree().setCursor(getDisplay().getSystemCursor(SWT.CURSOR_ARROW));
        treeViewer.setUseHashlookup(true);
        if (rootNode.getParentNode() == null) {
            //this.treeViewer.setAutoExpandLevel(2);
        }
        treeViewer.setLabelProvider(new DatabaseNavigatorLabelProvider(treeViewer));
        treeViewer.setContentProvider(new DatabaseNavigatorContentProvider(this, showRoot));

        treeViewer.setInput(new DatabaseNavigatorContent(rootNode));

        ColumnViewerToolTipSupport.enableFor(treeViewer);

        initEditor();
    }

    public boolean isFiltering() {
        return isFiltering;
    }


    protected TreeViewer doCreateTreeViewer(Composite parent, int style) {
        checkEnabled = (style & SWT.CHECK) != 0;

        // Create tree
        int treeStyle = SWT.H_SCROLL | SWT.V_SCROLL | style;
        if (checkEnabled) {
            return new CheckboxTreeViewer(parent, treeStyle);
        } else {
            //return new CustomFilteredTree(treeStyle).getViewer();
            return new TreeViewer(parent, treeStyle) {
                @Override
                public ISelection getSelection() {
                    ISelection selection = super.getSelection();
                    return selection.isEmpty() && defaultSelection != null ? defaultSelection : selection;
                }
                protected void handleTreeExpand(TreeEvent event) {
                    // Disable redraw during expand (its blinking)
                    getTree().setRedraw(false);
                    try {
                        super.handleTreeExpand(event);
                    } finally {
                        getTree().setRedraw(true);
                    }
                }
                protected void handleTreeCollapse(TreeEvent event) {
                    getTree().setRedraw(false);
                    try {
                        super.handleTreeCollapse(event);
                    } finally {
                        getTree().setRedraw(true);
                    }
                }
            };
        }
    }

    public DBNNode getModel()
    {
        DatabaseNavigatorContent content = (DatabaseNavigatorContent) this.treeViewer.getInput();
        return content.getRootNode();
    }

    private void initEditor()
    {
        Tree treeControl = this.treeViewer.getTree();

        treeEditor = new TreeEditor(treeControl);
        treeEditor.horizontalAlignment = SWT.LEFT;
        treeEditor.verticalAlignment = SWT.TOP;
        treeEditor.grabHorizontal = false;
        treeEditor.minimumWidth = 50;

        //treeControl.addSelectionListener(new TreeSelectionAdapter());
        if (!checkEnabled) {
            // Add rename listener only for non CHECK trees
            treeControl.addMouseListener(new TreeSelectionAdapter());
        }
    }

    @NotNull
    public TreeViewer getViewer()
    {
        return treeViewer;
    }

    @Override
    public void nodeChanged(final DBNEvent event)
    {
        switch (event.getAction()) {
            case ADD:
            case REMOVE: {
                final DBNNode node = event.getNode();
                final DBNNode parentNode = node.getParentNode();
                if (parentNode != null) {
                    DBeaverUI.syncExec(new Runnable() {
                        @Override
                        public void run() {
                            if (!treeViewer.getControl().isDisposed()) {
                                if (!parentNode.isDisposed()) {
                                    treeViewer.refresh(getViewerObject(parentNode));
                                    if (event.getNodeChange() == DBNEvent.NodeChange.SELECT) {
                                        treeViewer.reveal(node);
                                        treeViewer.setSelection(new StructuredSelection(node));
                                    }
                                }
                            }
                        }
                    });
                }
                break;
            }
            case UPDATE:
                DBeaverUI.syncExec(new Runnable() {
                    @Override
                    public void run() {
                        if (!treeViewer.getControl().isDisposed() && !treeViewer.isBusy()) {
                            if (event.getNode() != null) {
                                switch (event.getNodeChange()) {
                                    case LOAD:
                                        treeViewer.refresh(getViewerObject(event.getNode()));
                                        expandNodeOnLoad(event.getNode());
                                        break;
                                    case UNLOAD:
                                        treeViewer.collapseToLevel(event.getNode(), -1);
                                        treeViewer.refresh(getViewerObject(event.getNode()));
                                        break;
                                    case REFRESH:
                                        treeViewer.refresh(getViewerObject(event.getNode()), true);
                                        break;
                                    case LOCK:
                                    case UNLOCK:
                                    case STRUCT_REFRESH:
                                        treeViewer.refresh(getViewerObject(event.getNode()));
                                        break;
                                }
                            } else {
                                log.warn("Null node object");
                            }
                        }
                    }
                });
                break;
            default:
                break;
        }
    }

    private void expandNodeOnLoad(final DBNNode node)
    {
        if (node instanceof DBNDataSource && DBeaverCore.getGlobalPreferenceStore().getBoolean(DBeaverPreferences.NAVIGATOR_EXPAND_ON_CONNECT)) {
            try {
                DBRRunnableWithResult<DBNNode> runnable = new DBRRunnableWithResult<DBNNode>() {
                    @Override
                    public void run(DBRProgressMonitor monitor) throws InvocationTargetException, InterruptedException
                    {
                        try {
                            result = findActiveNode(monitor, node);
                        } catch (DBException e) {
                            throw new InvocationTargetException(e);
                        }
                    }
                };
                DBeaverUI.runInProgressService(runnable);
                if (runnable.getResult() != null) {
                    showNode(runnable.getResult());
                    treeViewer.expandToLevel(runnable.getResult(), 1);
                }
            } catch (InvocationTargetException e) {
                log.error("Can't expand node", e.getTargetException());
            } catch (InterruptedException e) {
                // skip it
            }
        }
    }

    private DBNNode findActiveNode(DBRProgressMonitor monitor, DBNNode node) throws DBException
    {
        DBNNode[] children = node.getChildren(monitor);
        if (!ArrayUtils.isEmpty(children)) {
            if (children[0] instanceof DBNContainer) {
                // Use only first folder to search
                return findActiveNode(monitor, children[0]);
            }
            for (DBNNode child : children) {
                if (NavigatorUtils.isDefaultElement(child)) {
                    return child;
                }
            }
        }

        return node;
    }

    Object getViewerObject(DBNNode node)
    {
        if (((DatabaseNavigatorContent) treeViewer.getInput()).getRootNode() == node) {
            return treeViewer.getInput();
        } else {
            return node;
        }
    }

    public void showNode(DBNNode node) {
        treeViewer.reveal(node);
        treeViewer.setSelection(new StructuredSelection(node));
    }

    public void reloadTree(final DBNNode rootNode)
    {
        DatabaseNavigatorTree.this.treeViewer.setInput(new DatabaseNavigatorContent(rootNode));
    }

    private class TreeSelectionAdapter implements MouseListener {

        private volatile TreeItem curSelection;
        private volatile RenameJob renameJob = new RenameJob();

        private volatile boolean doubleClick = false;

        @Override
        public synchronized void mouseDoubleClick(MouseEvent e)
        {
            curSelection = null;
            renameJob.canceled = true;
        }

        @Override
        public void mouseDown(MouseEvent e)
        {
        }

        @Override
        public void mouseUp(MouseEvent e)
        {
            if ((e.stateMask & SWT.BUTTON1) == 0) {
                curSelection = null;
                return;
            }
            changeSelection(e);
        }

        public void changeSelection(MouseEvent e) {
            disposeOldEditor();
            final TreeItem newSelection = treeViewer.getTree().getItem(new Point(e.x, e.y));
            if (newSelection == null) {
                return;
            }

            if (!(newSelection.getData() instanceof DBNNode) ||
                !(ActionUtils.isCommandEnabled(IWorkbenchCommandConstants.FILE_RENAME, DBeaverUI.getActiveWorkbenchWindow().getActivePage().getActivePart().getSite()))) {
                curSelection = null;
                return;
            }
            if (curSelection != null && curSelection == newSelection && renameJob.selection == null) {
                renameJob.selection = curSelection;
                renameJob.schedule(1000);
            }
            curSelection = newSelection;
        }

        private class RenameJob extends AbstractUIJob {
            private volatile boolean canceled = false;
            public TreeItem selection;

            public RenameJob()
            {
                super("Rename ");
            }

            @Override
            protected IStatus runInUIThread(DBRProgressMonitor monitor)
            {
                try {
                    if (!treeViewer.getTree().isDisposed() && treeViewer.getTree().isFocusControl() && curSelection == selection && !canceled) {
                        final TreeItem itemToRename = selection;
                        DBeaverUI.asyncExec(new Runnable() {
                            @Override
                            public void run() {
                                renameItem(itemToRename);
                            }
                        });
                    }
                } finally {
                    canceled = false;
                    selection = null;
                }
                return Status.OK_STATUS;
            }

        }
    }

    private void renameItem(final TreeItem item)
    {
        // Clean up any previous editor control
        disposeOldEditor();
        if (item.isDisposed()) {
            return;
        }
        final DBNNode node = (DBNNode) item.getData();

        Text text = new Text(treeViewer.getTree(), SWT.BORDER);
        text.setText(node.getNodeName());
        text.selectAll();
        text.setFocus();
        text.addFocusListener(new FocusAdapter() {
            @Override
            public void focusLost(FocusEvent e)
            {
                disposeOldEditor();
            }
        });
        text.addKeyListener(new KeyAdapter() {
            @Override
            public void keyPressed(KeyEvent e)
            {
                if (e.keyCode == SWT.CR) {
                    Text text = (Text) treeEditor.getEditor();
                    final String newName = text.getText();
                    disposeOldEditor();
                    treeViewer.getTree().setFocus();
                    if (!CommonUtils.isEmpty(newName) && !newName.equals(node.getNodeName())) {
                        NavigatorHandlerObjectRename.renameNode(DBeaverUI.getActiveWorkbenchWindow(), node, newName);
                    }
                } else if (e.keyCode == SWT.ESC) {
                    disposeOldEditor();
                    treeViewer.getTree().setFocus();
                }
            }
        });
        final Rectangle itemBounds = item.getBounds(0);
        final Rectangle treeBounds = treeViewer.getTree().getBounds();
        treeEditor.minimumWidth = Math.max(itemBounds.width, 50);
        treeEditor.minimumWidth = Math.min(treeEditor.minimumWidth, treeBounds.width - (itemBounds.x - treeBounds.x) - item.getImageBounds(0).width - 4);

        treeEditor.setEditor(text, item, 0);
    }

    private void disposeOldEditor()
    {
        Control oldEditor = treeEditor.getEditor();
        if (oldEditor != null) oldEditor.dispose();
    }

    private static class TreeFilter extends PatternFilter {
        @Override
        public boolean isElementVisible(Viewer treeViewer, Object element) {
            if (element instanceof DBNDatabaseItem || element instanceof DBNResource) {
                Object parent = ((ITreeContentProvider) ((AbstractTreeViewer) treeViewer)
                    .getContentProvider()).getParent(element);
                if (parent != null && isLeafMatch(treeViewer, parent)) {
                    return true;
                }
                return isParentMatch(treeViewer, element) || isLeafMatch(treeViewer, element);
            } else {
                return true;
            }
        }
    }

    private class TreeRefreshJobListener extends JobChangeAdapter {
        @Override
        public void aboutToRun(IJobChangeEvent event) {
            isFiltering = true;
        }

        @Override
        public void done(IJobChangeEvent event) {
            isFiltering = false;
        }
    }

    private class CustomFilteredTree extends FilteredTree {
        public CustomFilteredTree(int treeStyle) {
            super(DatabaseNavigatorTree.this, treeStyle, new TreeFilter(), true);
        }

        protected WorkbenchJob doCreateRefreshJob() {
            WorkbenchJob job = super.doCreateRefreshJob();
            job.addJobChangeListener(new TreeRefreshJobListener());
            return job;
        }
    }
}
