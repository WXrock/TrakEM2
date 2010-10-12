package ini.trakem2.utils;

import java.awt.Color;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.event.ActionEvent;
import java.awt.event.ActionListener;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.geom.AffineTransform;
import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.Vector;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.atomic.AtomicInteger;

import javax.swing.JFrame;
import javax.swing.JMenuItem;
import javax.swing.JPopupMenu;
import javax.swing.JScrollPane;
import javax.swing.JTabbedPane;
import javax.swing.JTable;
import javax.swing.SwingUtilities;
import javax.swing.table.AbstractTableModel;
import javax.swing.table.DefaultTableCellRenderer;
import javax.swing.table.DefaultTableModel;
import javax.swing.table.TableModel;

import ini.trakem2.Project;
import ini.trakem2.display.AreaTree;
import ini.trakem2.display.Connector;
import ini.trakem2.display.Display;
import ini.trakem2.display.Display3D;
import ini.trakem2.display.Displayable;
import ini.trakem2.display.LayerSet;
import ini.trakem2.display.Node;
import ini.trakem2.display.Tag;
import ini.trakem2.display.Tree;
import ini.trakem2.display.Treeline;
import ini.trakem2.display.ZDisplayable;
import ini.trakem2.parallel.TaskFactory;
import ini.trakem2.persistence.FSLoader;

public class Merger {
	/** Take two projects and find out what is different among them,
	 *  independent of id. */
	static public final void compare(final Project p1, final Project p2) {
		Utils.log("Be warned: only Treeline, AreaTree and Connector are considered at the moment.");

		final LayerSet ls1 = p1.getRootLayerSet(),
					   ls2 = p2.getRootLayerSet();

		final Collection<ZDisplayable> zds1 = ls1.getZDisplayables(),
									   zds2 = ls2.getZDisplayables();

		final HashSet<Class<?>> accepted = new HashSet<Class<?>>();
		accepted.add(Treeline.class);
		accepted.add(AreaTree.class);
		accepted.add(Connector.class);

		final HashMap<Displayable,List<Change>> matched = new HashMap<Displayable,List<Change>>();
		
		// For every Displayable in p1, find a corresponding Displayable in p2
		// or at least one that is similar.
		
		final HashSet<ZDisplayable> empty1 = new HashSet<ZDisplayable>(),
									empty2 = new HashSet<ZDisplayable>();

		final HashSet<ZDisplayable> unmatched1 = new HashSet<ZDisplayable>(),
									unmatched2 = new HashSet<ZDisplayable>(zds2);

		// Remove instances of classes not accepted
		for (final Iterator<ZDisplayable> it = unmatched2.iterator(); it.hasNext(); ) {
			ZDisplayable zd = it.next();
			if (!accepted.contains(zd.getClass())) {
				it.remove();
				continue;
			}
			if (zd.isDeletable()) {
				it.remove();
				empty2.add(zd);
			}
		}
		zds2.removeAll(empty2);
		
		final AtomicInteger counter = new AtomicInteger(0);

		try {
			ini.trakem2.parallel.Process.unbound(zds1,
					new TaskFactory<ZDisplayable, Object>() {
						@Override
						public Object process(final ZDisplayable zd1) {
							Utils.showProgress(counter.getAndIncrement() / (float)zds1.size());
							if (!accepted.contains(zd1.getClass())) {
								Utils.log("Ignoring: [A] " + zd1);
								return null;
							}
							if (zd1.isDeletable()) {
								synchronized (empty1) {
									empty1.add(zd1);
								}
								return null;
							}
							final List<Change> cs = new ArrayList<Change>();
							for (final ZDisplayable zd2 : zds2) {
								// Same class?
								if (zd1.getClass() != zd2.getClass()) continue;
								if (zd1 instanceof Tree<?> && zd2 instanceof Tree<?>) {
									Change c = compareTrees(zd1, zd2);
									if (c.hasSimilarNodes()) {
										cs.add(c);
										if (1 == cs.size()) {
											synchronized (matched) {
												matched.put(zd1, cs);
											}
										}
										synchronized (unmatched2) {
											unmatched2.remove(zd2);
										}
									}
									// debug
									if (zd1.getId() == zd2.getId()) {
										Utils.log("zd1 #" + zd1.getId() + " is similar to #" + zd2.getId() + ": " + c.hasSimilarNodes());
									}
								}
							}
							if (cs.isEmpty()) {
								synchronized (unmatched1) {
									unmatched1.add(zd1);
								}
							}
							return null;
						}
					});
		} catch (Exception e) {
			IJError.print(e);
		}
		
		Utils.showProgress(1); // reset

		Utils.log("matched.size(): " + matched.size());
		
		makeGUI(p1, p2, empty1, empty2, matched, unmatched1, unmatched2);
	}


	private static class Change {
		ZDisplayable d1, d2;
		/** If the title is different. */
		boolean title = false;
		/** If the AffineTransform is different. */
		boolean transform = false;
		/** If the tree has been rerooted. */
		boolean root = false;
		/** The difference in the number of nodes, in percent */
		int diff = 0;
		/** If the tags are all the same for all identical nodes. */
		HashSet<Tags> different_tags = new HashSet<Tags>();
		/** Number of nodes in d1 found also in d2 (independent of tags). */
		int common_nodes = 0;
		
		int n_nodes_d1 = 0;
		int n_nodes_d2 = 0;

		Change(ZDisplayable d1, ZDisplayable d2) {
			this.d1 = d1;
			this.d2 = d2;
		}

		boolean identical() {
			return !title && !transform && !root && 0 == diff
			    && different_tags.isEmpty() && n_nodes_d1 == n_nodes_d2 && n_nodes_d1 == common_nodes;
		}

		boolean hasSimilarNodes() {
			return common_nodes > 0;
		}
	}

	private static class Tags {
		final Node<?> nd1, nd2;
		Tags(Node<?> nd1, Node<?> nd2) {
			this.nd1 = nd1;
			this.nd2 = nd2;
		}
		String getDifferentTags() {
			StringBuilder sb = new StringBuilder();
			Set<Tag> tags1 = nd1.getTags(),
					 tags2 = nd2.getTags();
			Set<Tag> diff_tags = new HashSet<Tag>(tags1);
			diff_tags.removeAll(tags2);
			for (final Tag t : diff_tags) {
				sb.append("[-]").append(t.toString()).append(", ");
			}
			diff_tags.clear();
			diff_tags.addAll(tags2);
			diff_tags.removeAll(tags1);
			if (diff_tags.size() > 0) {
				if (sb.length() > 0) sb.append("  /  ");
				for (final Tag t : diff_tags) {
					sb.append("[+]").append(t.toString()).append(", ");
				}
			}
			return sb.toString();
		}
	}

	/** Just so that hashCode will be 0 always and HashMap will be forced to use equals instead,
	 *  and also using the affine. */
	static private final class WNode {
		final Node<?> nd;
		final float x, y;
		final double z;
		WNode(Node<?> nd, AffineTransform aff) {
			this.nd = nd;
			float[] f = new float[]{nd.getX(), nd.getY()};
			aff.transform(f, 0, f, 0, 1);
			this.x = f[0];
			this.y = f[1];
			this.z = nd.getLayer().getZ();
		}
		@Override
		public final int hashCode() {
			return 0;
		}
		@Override
		public final boolean equals(Object ob) {
			final WNode o = (WNode)ob;
			return same(x, o.x) && same(y, o.y) && same(z, o.z);
		}
		private final boolean same(final float f1, final float f2) {
			return Math.abs(f1 - f2) < 0.01;
		}
		private final boolean same(final double f1, final double f2) {
			return Math.abs(f1 - f2) < 0.01;
		}
	}

	static private final HashSet<WNode> asNNodes(Collection<Node<?>> nds, AffineTransform aff) {
		final HashSet<WNode> col = new HashSet<WNode>();
		for (final Node<?> nd : nds) {
			col.add(new WNode(nd, aff));
		}
		return col;
	}

	private static Change compareTrees(ZDisplayable zd1, ZDisplayable zd2) {
		final Tree<?> t1 = (Tree<?>)zd1,
					  t2 = (Tree<?>)zd2;
		Change c = new Change(zd1, zd2);
		// Title:
		if (!t1.getTitle().equals(t2.getTitle())) {
			c.title = true;
		}
		// Transform:
		if (!t2.getAffineTransform().equals(t2.getAffineTransform())) {
			c.transform = true;
		}
		// Data
		final HashSet<WNode> nds1 = asNNodes((Collection<Node<?>>) (Collection) t1.getRoot().getSubtreeNodes(), t1.getAffineTransform());
		final HashMap<WNode,WNode> nds2 = new HashMap<WNode,WNode>();
		for (final Node<?> nd : t2.getRoot().getSubtreeNodes()) {
			WNode nn = new WNode(nd, t2.getAffineTransform());
			nds2.put(nn, nn);
		}
		
		// What proportion of nodes is similar?
		final HashSet<WNode> diff = new HashSet<WNode>(nds1);
		diff.removeAll(nds2.keySet());

		c.common_nodes = nds1.size() - diff.size();

		c.n_nodes_d1 = nds1.size();
		c.n_nodes_d2 = nds2.size();
		
		// Same amount of nodes?
		c.diff = nds1.size() - nds2.size();

		if (t1.getId() == t2.getId()) {
			Utils.log2("nds1.size(): " + nds1.size() + ", nds2.size(): " + nds2.size() + ", diff.size(): " + diff.size()
					+ ", c.common_nodes: " + c.common_nodes + ", c.diff: " + c.diff);
		}

		// is the root the same? I.e. has it been re-rooted?
		if (nds1.size() > 0) {
			c.root = t1.getRoot().equals(t2.getRoot());
		}
		// what about tags?
		for (final WNode nd1 : nds1) {
			final WNode nd2 = nds2.get(nd1);
			if (null == nd2) continue;
			if (nd1.nd.hasSameTags(nd2.nd)) continue;
			c.different_tags.add(new Tags(nd1.nd, nd2.nd));
		}

		return c;
	}

	private static class Row {
		static int COLUMNS = 13;
		Change c;
		boolean sent = false;
		Row(Change c) {
			this.c = c;
		}
		Object getColumn(int i) {
			switch (i) {
			case 0:
				return c.d1.getClass().getSimpleName();
			case 1:
				return c.d1.getId();
			case 2:
				return c.d1.getProject().getMeaningfulTitle2(c.d1);
			case 3:
				return !c.title;
			case 4:
				return !c.transform;
			case 5:
				return !c.root;
			case 6:
				return c.common_nodes;
			case 7:
				return c.diff;
			case 8:
				return c.different_tags.size(); // number of nodes with different tags
			case 9:
				return c.d2.getId();
			case 10:
				return c.d2.getProject().getMeaningfulTitle2(c.d2);
			case 11:
				return c.identical();
			case 12:
				return sent;
			default:
				Utils.log("Row.getColumn: Don't know what to do with column " + i);
				return null;
			}
		}
		Color getColor() {
			if (c.identical()) return Color.white;
			if (c.hasSimilarNodes()) return Color.pink;
			return Color.red.brighter();
		}
		public void sent() {
			sent = true;
		}
		static private String getColumnName(int col) {
			switch (col) {
			case 0: return "Type";
			case 1: return "id 1";
			case 2: return "title 1";
			case 3: return "=title?";
			case 4: return "=affine?";
			case 5: return "=root?";
			case 6: return "N similar nodes";
			case 7: return "N diff";
			case 8: return "N diff tags";
			case 9: return "id 2";
			case 10: return "title 2";
			case 11: return "Identical?";
			case 12: return "sent";
			default:
				Utils.log("Row.getColumnName: Don't know what to do with column " + col);
				return null;
			}
		}
	}

	private static void makeGUI(Project p1, Project p2,
			HashSet<ZDisplayable> empty1, HashSet<ZDisplayable> empty2,
			HashMap<Displayable,List<Change>> matched,
			HashSet<ZDisplayable> unmatched1,
			HashSet<ZDisplayable> unmatched2) {

		final ArrayList<Row> rows = new ArrayList<Row>();
		for (Map.Entry<Displayable,List<Change>> e : matched.entrySet()) {
			for (Change c : e.getValue()) {
				rows.add(new Row(c));
			}
			if (e.getValue().size() > 1) {
				Utils.log("More than one assigned to " + e.getKey());
			}
		}
		JTabbedPane tabs = new JTabbedPane();

		final Table table = new Table();
		tabs.addTab("Matched", new JScrollPane(table));

		JTable tu1 = new JTable(new SingleColumnModel(unmatched1, "Unmatched 1"));
		JTable tu2 = new JTable(new SingleColumnModel(unmatched2, "Unmatched 2"));
		JTable tu3 = new JTable(new SingleColumnModel(empty1, "Empty 1"));
		JTable tu4 = new JTable(new SingleColumnModel(empty2, "Empty 2"));
		
		tabs.addTab("Unmatched 1", new JScrollPane(tu1));
		tabs.addTab("Unmatched 2", new JScrollPane(tu2));
		tabs.addTab("Empty 1", new JScrollPane(tu3));
		tabs.addTab("Empty 2", new JScrollPane(tu4));
		
		MouseAdapter listener = new MouseAdapter() {
			@Override
			public void mousePressed(MouseEvent me) {
				JTable src = (JTable)me.getSource();
				TableModel model = src.getModel();
				int row = src.rowAtPoint(me.getPoint()),
					col = src.columnAtPoint(me.getPoint());
				if (2 == me.getClickCount()) {
					Object ob = model.getValueAt(row, col);
					if (ob instanceof ZDisplayable) {
						ZDisplayable zd = (ZDisplayable)ob;
						Display display = Display.getFront(zd.getProject()); // if it exists, make it front
						Display.showCentered(zd.getFirstLayer(), zd, true, false); // also select
					}
				}
			}
		};
		tu1.addMouseListener(listener);
		tu2.addMouseListener(listener);
		tu3.addMouseListener(listener);
		tu4.addMouseListener(listener);

		for (int i=0; i<tabs.getTabCount(); i++) {
			if (null == tabs.getTabComponentAt(i)) {
				Utils.log2("null at " + i);
				continue;
			}
			tabs.getTabComponentAt(i).setPreferredSize(new Dimension(800, 800));
		}

		String xml1 = new File(((FSLoader)p1.getLoader()).getProjectXMLPath()).getName();
		String xml2 = new File(((FSLoader)p2.getLoader()).getProjectXMLPath()).getName();
		JFrame frame = new JFrame("1: " + xml1 + "  ||  2: " + xml2);
		frame.getContentPane().add(tabs);
		frame.pack();
		frame.setVisible(true);

		// so the bullshit starts: any other way to set the model fails, because it tries to render it while setting it
		SwingUtilities.invokeLater(new Runnable() { public void run() {
			table.setModel(new Model(rows));
			CustomCellRenderer cc = new CustomCellRenderer();
			for (int i=0; i<Row.COLUMNS; i++) {
				table.setDefaultRenderer(table.getColumnClass(i), cc);
			}
		}});
	}

	static private class SingleColumnModel extends DefaultTableModel {
		SingleColumnModel(final HashSet<?> ds, final String title) {
			super(asOneColumn(ds), new Vector(Arrays.asList(new String[]{title})));
		}
		@Override
		public boolean isCellEditable(int row, int col) {
			return false;
		}
		@Override
		public void setValueAt(Object value, int row, int col) {}
	}

	static private final Vector asOneColumn(final HashSet<?> ds) {
		Vector v = new Vector();
		for (Object d : ds) {
			Vector w = new Vector();
			w.add(d);
			v.add(w);
		}
		return v;
	}

	static private final class Model extends AbstractTableModel {
		ArrayList<Row> rows;
		Model(ArrayList<Row> rows) {
			this.rows = rows;
		}
		public void sortByColumn(final int column, final boolean descending) {
			final ArrayList<Row> rows = new ArrayList<Row>(this.rows);
			Collections.sort(rows, new Comparator<Row>() {
				public int compare(Row o1, Row o2) {
					if (descending) {
						Row tmp = o1;
						o1 = o2;
						o2 = tmp;
					}
					Object val1 = getValueAt(rows.indexOf(o1), column);
					Object val2 = getValueAt(rows.indexOf(o2), column);
					return ((Comparable)val1).compareTo((Comparable)val2);
				}
			});
			this.rows = rows; // swap
			fireTableDataChanged();
			fireTableStructureChanged();
		}
		@Override
		public Object getValueAt(int row, int col) {
			return rows.get(row).getColumn(col);
		}
		@Override
		public int getRowCount() {
			if (null == rows) return 0;
			return rows.size();
		}
		@Override
		public int getColumnCount() {
			return Row.COLUMNS;
		}
		@Override
		public boolean isCellEditable(int row, int col) {
			return false;
		}
		@Override
		public void setValueAt(Object value, int row, int col) {}
		@Override
		public String getColumnName(int col) {
			return Row.getColumnName(col);
		}
	}
	
	static private final class CustomCellRenderer extends DefaultTableCellRenderer {
		public Component getTableCellRendererComponent(JTable table, Object value, boolean isSelected, boolean hasFocus, int row, int column) {
			final Component c = super.getTableCellRendererComponent(table, value, isSelected, hasFocus, row, column);
			final Row r = ((Model)table.getModel()).rows.get(row);
			if (12 == column && r.sent) {
				c.setForeground(Color.white);
				c.setBackground(Color.green);
				return c;
			}
			if (isSelected) {
				setForeground(table.getSelectionForeground());
				setBackground(table.getSelectionBackground());
			} else {
				c.setForeground(Color.black);
				c.setBackground(r.getColor());
			}
			return c;
		}
	}

	static private final class Table extends JTable {
		private int last_sorted_column = -1;
		private boolean last_sorting_order = true; // descending == true
		Table() {
			super();
			getTableHeader().addMouseListener(new MouseAdapter() {
				public void mouseClicked(MouseEvent me) { // mousePressed would fail to repaint due to asynch issues
					if (2 != me.getClickCount()) return;
					int viewColumn = getColumnModel().getColumnIndexAtX(me.getX());
					int column = convertColumnIndexToModel(viewColumn);
					if (-1 == column) return;
					((Model)getModel()).sortByColumn(column, me.isShiftDown());
					last_sorted_column = column;
					last_sorting_order = me.isShiftDown();
				}
			});
			this.addMouseListener(new MouseAdapter() {
				public void mousePressed(MouseEvent me) {
					final int row = Table.this.rowAtPoint(me.getPoint());
					final int col = Table.this.columnAtPoint(me.getPoint());
					final Row r = ((Model)getModel()).rows.get(row);
					if (Utils.isPopupTrigger(me)) {
						JPopupMenu popup = new JPopupMenu();
						final JMenuItem send12 = new JMenuItem("Send 1 to 2"); popup.add(send12);
						final JMenuItem send21 = new JMenuItem("Send 2 to 1"); popup.add(send21);
						popup.addSeparator();
						final JMenuItem select = new JMenuItem("Select each in its own display"); popup.add(select);
						final JMenuItem select2 = new JMenuItem("Select and center each in its own display"); popup.add(select2);
						final JMenuItem show3D = new JMenuItem("Show both in 3D"); popup.add(show3D);
						ActionListener listener = new ActionListener() {
							@Override
							public void actionPerformed(ActionEvent e) {
								if (select == e.getSource()) {
									select(r.c.d1);
									select(r.c.d2);
								} else if (select2 == e.getSource()) {
									select2(r.c.d1);
									select2(r.c.d2);
								} else if (show3D == e.getSource()) {
									show3D(r.c.d1);
									show3D(r.c.d2);
								} else if (send12 == e.getSource()) {
									if (send(r.c.d1, r.c.d2)) {
										r.sent();
									}
								} else if (send21 == e.getSource()) {
									if (send(r.c.d2, r.c.d1)) {
										r.sent();
									}
								}
							}
						};
						for (final JMenuItem item : new JMenuItem[]{select, select2, show3D, send12, send21}) {
							item.addActionListener(listener);
						}
						popup.show(Table.this, me.getX(), me.getY());
					}
				}
			});
		}
		private void select(ZDisplayable d) {
			Display display = Display.getFront(d.getProject());
			if (null == display) {
				Utils.log("No displays open for project " + d.getProject());
			} else {
				display.select(d);
			}
		}
		private void select2(ZDisplayable d) {
			Display display = Display.getFront(d.getProject());
			if (null == display) {
				Utils.log("No displays open for project " + d.getProject());
			} else {
				Display.showCentered(d.getFirstLayer(), d, true, false); // also select
			}
		}
		private void show3D(Displayable d) {
			Display3D.show(d.getProject().findProjectThing(d));
		}
		/** Replace d2 with d1 in d2's project. */
		private boolean send(ZDisplayable d1, ZDisplayable d2) {
			String xml1 = new File(((FSLoader)d1.getProject().getLoader()).getProjectXMLPath()).getName();
			String xml2 = new File(((FSLoader)d2.getProject().getLoader()).getProjectXMLPath()).getName();
			if (!Utils.check("Really replace " + d2 + " (" + xml2 + ")\n" +
					    	 "with " + d1 + " (" + xml1 + ") ?")) {
				return false;
			}
			ZDisplayable copy = (ZDisplayable) d1.clone(d2.getProject(), false);
			LayerSet ls = d2.getLayerSet();
			ls.addChangeTreesStep();
			ls.add(copy);
			d2.getProject().getProjectTree().addSibling(d2, copy);
			d2.getProject().remove(d2);
			ls.addChangeTreesStep();
			Utils.log("Replaced " + d2 + " (from " + xml2 + ")\n" +
					  "    with " + d1 + " (from " + xml1 + ")");
			update();
			return true;
		}
		private void update() {
			((Model)getModel()).fireTableDataChanged();
			((Model)getModel()).fireTableStructureChanged();
		}
	}

}