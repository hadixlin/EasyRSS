/*******************************************************************************
 * Copyright (c) 2012 Pursuer (http://pursuer.me).
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the GNU Public License v3.0
 * which accompanies this distribution, and is available at
 * http://www.gnu.org/licenses/gpl.html
 * 
 * Contributors:
 *     Pursuer - initial API and implementation
 ******************************************************************************/

package com.pursuer.reader.easyrss;

import java.net.URLEncoder;
import java.util.LinkedList;
import java.util.List;

import com.pursuer.reader.easyrss.R;
import com.pursuer.reader.easyrss.data.DataMgr;
import com.pursuer.reader.easyrss.data.DataUtils;
import com.pursuer.reader.easyrss.data.Item;
import com.pursuer.reader.easyrss.data.ItemState;
import com.pursuer.reader.easyrss.data.Tag;
import com.pursuer.reader.easyrss.data.readersetting.SettingDescendingItemsOrdering;
import com.pursuer.reader.easyrss.data.readersetting.SettingFontSize;
import com.pursuer.reader.easyrss.data.readersetting.SettingMarkAllAsReadConfirmation;
import com.pursuer.reader.easyrss.data.readersetting.SettingSyncMethod;
import com.pursuer.reader.easyrss.listadapter.AbsListItem;
import com.pursuer.reader.easyrss.listadapter.ListAdapter;
import com.pursuer.reader.easyrss.listadapter.ListItemEndEnabled;
import com.pursuer.reader.easyrss.listadapter.ListItemItem;
import com.pursuer.reader.easyrss.listadapter.ListItemTitle;
import com.pursuer.reader.easyrss.listadapter.OnItemTouchListener;
import com.pursuer.reader.easyrss.network.ItemDataSyncer;
import com.pursuer.reader.easyrss.network.NetworkMgr;
import com.pursuer.reader.easyrss.network.NetworkUtils;
import com.pursuer.reader.easyrss.view.AbsViewCtrl;

import android.annotation.SuppressLint;
import android.app.AlertDialog;
import android.app.ProgressDialog;
import android.content.ContentResolver;
import android.content.Context;
import android.content.DialogInterface;
import android.database.Cursor;
import android.net.Uri;
import android.os.Handler;
import android.os.Message;
import android.view.LayoutInflater;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.View.OnClickListener;
import android.widget.CheckBox;
import android.widget.CompoundButton;
import android.widget.CompoundButton.OnCheckedChangeListener;
import android.widget.ListView;
import android.widget.Toast;

public class FeedViewCtrl extends AbsViewCtrl implements ItemListWrapperListener {
    private class FeedListAdapterListener implements OnItemTouchListener {
        private float lastX;
        private float lastY;
        private int motionSlop;

        public FeedListAdapterListener() {
            final ViewConfiguration configuration = ViewConfiguration.get(FeedViewCtrl.this.context);
            this.lastX = 0f;
            this.lastY = 0f;
            this.motionSlop = configuration.getScaledTouchSlop();
        }

        @Override
        public void onItemTouched(final ListAdapter adapter, final AbsListItem item, final MotionEvent event) {
            if (listener == null || !isAvailable || (item instanceof ListItemTitle)) {
                return;
            }
            switch (event.getAction()) {
            case MotionEvent.ACTION_DOWN:
                if (item instanceof ListItemItem) {
                    lastX = event.getX();
                    lastY = event.getY();
                    final Message msg = handler.obtainMessage(MSG_ITEM_LONG_CLICK, item.getId());
                    handler.sendMessageDelayed(msg, 600);
                }
                break;
            case MotionEvent.ACTION_CANCEL:
                if (item instanceof ListItemItem) {
                    handler.removeMessages(MSG_ITEM_LONG_CLICK);
                }
                break;
            case MotionEvent.ACTION_MOVE:
                if (item instanceof ListItemItem) {
                    final float curX = event.getX();
                    final float curY = event.getY();
                    if (Math.abs(curY - lastY) > motionSlop || Math.abs(curX - lastX) > motionSlop) {
                        handler.removeMessages(MSG_ITEM_LONG_CLICK);
                    }
                }
                break;
            case MotionEvent.ACTION_UP:
                if (item instanceof ListItemItem && handler.hasMessages(MSG_ITEM_LONG_CLICK)) {
                    handler.removeMessages(MSG_ITEM_LONG_CLICK);
                    listener.onItemSelected(item.getId());
                } else if (item instanceof ListItemEndEnabled) {
                    lstWrapper.updateItemEndLoading();
                    NetworkMgr.getInstance().startSync(syncer);
                    Toast.makeText(context, R.string.MsgLoadingMoreItems, Toast.LENGTH_LONG).show();
                }
                break;
            default:
                break;
            }
        }
    }

    final private static String ITEM_PROJECTION[] = new String[] { Item._UID, Item._TITLE, ItemState._ISREAD,
            ItemState._ISSTARRED, Item._TIMESTAMP, Item._SOURCETITLE };
    final private static int MSG_ITEM_LONG_CLICK = 0;

    private static String appendCondition(final String condition, final String newCondition) {
        return (condition.length() == 0) ? newCondition : (condition + " AND " + newCondition);
    }

    private static void appendCondition(final StringBuilder builder, final String newCondition) {
        if (builder.length() > 0) {
            builder.append(" AND ");
        }
        builder.append(newCondition);
    }

    final private boolean isDecendingOrdering;
    final private ItemListWrapper lstWrapper;
    final private Handler handler;
    private ItemDataSyncer syncer;
    private final String uid;
    private final int viewType;
    private boolean isAvailable;
    private boolean isEnd;
    private long lastTimestamp;
    private String lastDateString;

    public FeedViewCtrl(final DataMgr dataMgr, final Context context, final String uid, final int viewType) {
        super(dataMgr, R.layout.feed, context);

        final int fontSize = new SettingFontSize(dataMgr).getData();
        final ListView feedList = (ListView) view.findViewById(R.id.FeedList);

        this.lstWrapper = new ItemListWrapper(feedList, fontSize);
        this.isDecendingOrdering = new SettingDescendingItemsOrdering(dataMgr).getData();
        this.uid = uid;
        this.viewType = viewType;
        this.isAvailable = false;
        this.isEnd = false;
        this.lastTimestamp = 0;

        lstWrapper.setListener(this);
        lstWrapper.setAdapterListener(new FeedListAdapterListener());
        this.handler = new Handler() {
            @Override
            public void handleMessage(final Message msg) {
                if (msg.what == MSG_ITEM_LONG_CLICK) {
                    final Integer pos = lstWrapper.getAdapter().getItemLocationById((String) msg.obj);
                    if (pos == null) {
                        return;
                    }
                    final AbsListItem absItem = lstWrapper.getAdapter().getItem(pos);
                    if (!(absItem instanceof ListItemItem)) {
                        return;
                    }
                    final ListItemItem item = (ListItemItem) absItem;
                    final AlertDialog.Builder builder = new AlertDialog.Builder(context);
                    final String[] popup = new String[4];
                    popup[0] = context.getString(item.isRead() ? R.string.TxtMarkAsUnread : R.string.TxtMarkAsRead);
                    popup[1] = context.getString(R.string.TxtMarkPreviousAsRead);
                    popup[2] = context.getString(item.isStarred() ? R.string.TxtRemoveStar : R.string.TxtAddStar);
                    popup[3] = context.getString(R.string.TxtSendTo);
                    builder.setItems(popup, new DialogInterface.OnClickListener() {
                        @Override
                        public void onClick(final DialogInterface dialog, final int id) {
                            switch (id) {
                            case 0:
                                if (item.isRead()) {
                                    dataMgr.markItemAsUnreadWithTransactionByUid(item.getId());
                                } else {
                                    dataMgr.markItemAsReadWithTransactionByUid(item.getId());
                                }
                                NetworkMgr.getInstance().startImmediateItemStateSyncing();
                                break;
                            case 1:
                                final ProgressDialog pDialog = ProgressDialog.show(context,
                                        context.getString(R.string.TxtWorking),
                                        context.getString(R.string.TxtMarkingPreviousItemsAsRead));
                                final Handler handler = new Handler() {
                                    @Override
                                    public void handleMessage(final Message msg) {
                                        pDialog.dismiss();
                                    }
                                };
                                final Thread thread = new Thread() {
                                    @Override
                                    public void run() {
                                        for (int i = 0; i <= pos; i++) {
                                            final AbsListItem item = lstWrapper.getAdapter().getItem(i);
                                            if (item instanceof ListItemItem) {
                                                final ListItemItem pItem = (ListItemItem) lstWrapper.getAdapter()
                                                        .getItem(i);
                                                if (!pItem.isRead()) {
                                                    dataMgr.markItemAsReadWithTransactionByUid(pItem.getId());
                                                }
                                            }
                                        }
                                        NetworkMgr.getInstance().startImmediateItemStateSyncing();
                                        handler.sendEmptyMessage(0);
                                    }
                                };
                                thread.setPriority(Thread.MIN_PRIORITY);
                                thread.start();
                                break;
                            case 2:
                                dataMgr.markItemAsStarredWithTransactionByUid(item.getId(), !item.isStarred());
                                Toast.makeText(context, item.isStarred() ? R.string.MsgUnstarred : R.string.MsgStarred,
                                        Toast.LENGTH_LONG).show();
                                NetworkMgr.getInstance().startImmediateItemStateSyncing();
                                break;
                            case 3:
                                DataUtils.sendTo(context, dataMgr.getItemByUid(item.getId()));
                                break;
                            default:
                            }
                        }
                    });
                    builder.show();
                }
            }
        };
    }

    private String getCondition(final long timestamp) {
        final StringBuilder builder = new StringBuilder();
        if (viewType == Home.VIEW_TYPE_UNREAD) {
            appendCondition(builder, ItemState._ISREAD + "=0");
        } else if (viewType == Home.VIEW_TYPE_STARRED) {
            appendCondition(builder, ItemState._ISSTARRED + "=1");
        }
        if (uid.length() > 0 && !DataUtils.isTagUid(uid)) {
            appendCondition(builder, Item._SOURCEURI + "=\"" + uid + "\"");
        }
        if (timestamp > 0) {
            if (isDecendingOrdering) {
                appendCondition(builder, Item._TIMESTAMP + "<" + timestamp);
            } else {
                appendCondition(builder, Item._TIMESTAMP + ">" + timestamp);
            }
        }
        return builder.toString();
    }

    public ListItemItem getLastItem(final String uid) {
        final ListAdapter adapter = lstWrapper.getAdapter();
        int location = adapter.getItemLocationById(uid) - 1;
        while (location >= 0 && !(adapter.getItem(location) instanceof ListItemItem)) {
            location--;
        }
        return (location < 0) ? null : (ListItemItem) adapter.getItem(location);
    }

    public ListItemItem getNextItem(final String uid) {
        final ListAdapter adapter = lstWrapper.getAdapter();
        int location = adapter.getItemLocationById(uid) + 1;
        if (location + 10 >= adapter.getCount()) {
            showItemList();
        }
        while (location < adapter.getCount() && !(adapter.getItem(location) instanceof ListItemItem)) {
            location++;
            if (location >= adapter.getCount()) {
                showItemList();
            }
        }
        return (location >= adapter.getCount()) ? null : (ListItemItem) adapter.getItem(location);
    }

    public String getUid() {
        return uid;
    }

    public int getViewType() {
        return viewType;
    }

    @Override
    public void handleOnSyncFinished(final String syncerType, final boolean succeeded) {
        if (syncerType.equals(ItemDataSyncer.class.getName())) {
            isEnd = false;
            showItemList();
        }
    }

    private void markAllAsRead() {
        dataMgr.removeOnItemUpdatedListener(lstWrapper);
        final ProgressDialog pDialog = ProgressDialog.show(context, context.getString(R.string.TxtWorking),
                context.getString(R.string.TxtMarkingAllItemsAsRead));
        final Handler handler = new Handler() {
            @Override
            public void handleMessage(final Message msg) {
                pDialog.dismiss();
                if (listener != null) {
                    listener.onBackNeeded();
                }
            }
        };
        final Thread thread = new Thread() {
            @Override
            public void run() {
                final ContentResolver resolver = context.getContentResolver();
                String condition = getCondition(0);
                condition = appendCondition(condition, ItemState._ISREAD + "=0");
                @SuppressWarnings("deprecation")
                final Uri uri = DataUtils.isTagUid(uid) ? Uri.withAppendedPath(Tag.CONTENT_URI,
                        "items/" + URLEncoder.encode(uid)) : Item.CONTENT_URI;
                final Cursor cur = resolver.query(uri, null, condition, null, null);
                final List<Item> items = new LinkedList<Item>();
                for (cur.moveToFirst(); !cur.isAfterLast(); cur.moveToNext()) {
                    items.add(Item.fromCursor(cur));
                }
                cur.close();
                dataMgr.markItemsAsReadWithTransaction(items);
                NetworkMgr.getInstance().startImmediateItemStateSyncing();
                handler.sendEmptyMessage(0);
            }
        };
        thread.setPriority(Thread.MIN_PRIORITY);
        thread.start();
    }

    @Override
    public void onActivate() {
        this.isAvailable = true;
    }

    @Override
    public void onCreate() {
        NetworkMgr.getInstance().addListener(this);
        dataMgr.addOnItemUpdatedListener(lstWrapper);

        showItemList();

        final View markAll = this.view.findViewById(R.id.BtnMarkAllAsRead);
        markAll.setOnClickListener(new OnClickListener() {
            @Override
            public void onClick(final View view) {
                final LayoutInflater inflater = (LayoutInflater) context
                        .getSystemService(Context.LAYOUT_INFLATER_SERVICE);
                final AlertDialog.Builder builder = new AlertDialog.Builder(context);
                final SettingMarkAllAsReadConfirmation sMark = new SettingMarkAllAsReadConfirmation(dataMgr);
                if (sMark.getData()) {
                    final View popupView = inflater.inflate(R.layout.mark_all_as_read_popup, null);
                    final CheckBox checkBox = (CheckBox) popupView.findViewById(R.id.CheckBoxDontShowAgain);
                    checkBox.setOnCheckedChangeListener(new OnCheckedChangeListener() {
                        @Override
                        public void onCheckedChanged(final CompoundButton buttonView, final boolean isChecked) {
                            popupView.findViewById(R.id.Hint).setVisibility(View.VISIBLE);
                            sMark.setData(dataMgr, !isChecked);
                            dataMgr.updateSetting(sMark.toSetting());
                        }
                    });
                    builder.setIcon(android.R.drawable.ic_dialog_info);
                    builder.setTitle(R.string.TxtConfirmation);
                    builder.setView(popupView);
                    builder.setPositiveButton(android.R.string.yes, new DialogInterface.OnClickListener() {
                        @Override
                        public void onClick(final DialogInterface dialog, final int which) {
                            markAllAsRead();
                        }
                    });
                    builder.setNegativeButton(android.R.string.no, null);
                    builder.show();
                } else {
                    markAllAsRead();
                }
            }
        });

        final View btnBack = this.view.findViewById(R.id.BtnBackHome);
        btnBack.setOnClickListener(new OnClickListener() {
            @Override
            public void onClick(final View view) {
                if (listener != null) {
                    listener.onBackNeeded();
                }
            }
        });
    }

    @Override
    public void onDeactivate() {
        this.isAvailable = false;
    }

    @Override
    public void onDestory() {
        NetworkMgr.getInstance().removeListener(this);
        dataMgr.removeOnItemUpdatedListener(lstWrapper);
    }

    @Override
    public void onNeedMoreItems() {
        showItemList();
    }

    private void showItemList() {
        if (isEnd) {
            return;
        }
        lstWrapper.removeItemEnd();
        final ContentResolver resolver = context.getContentResolver();
        @SuppressWarnings("deprecation")
        final Uri uri = DataUtils.isTagUid(uid) ? Uri.withAppendedPath(Tag.CONTENT_URI,
                "items/" + URLEncoder.encode(uid)) : Item.CONTENT_URI;
        final Cursor cur = resolver.query(uri, ITEM_PROJECTION, getCondition(lastTimestamp), null, Item._TIMESTAMP
                + ((isDecendingOrdering) ? " DESC LIMIT 20" : " LIMIT 20"));
        int count = 0;
        for (cur.moveToFirst(); !cur.isAfterLast(); cur.moveToNext()) {
            final Item item = Item.fromCursor(cur);
            count++;
            lastTimestamp = item.getTimestamp();
            final String curDateString = Utils.timestampToTimeAgo(context, item.getTimestamp());
            if (!curDateString.equals(lastDateString)) {
                String s;
                switch (viewType) {
                case Home.VIEW_TYPE_ALL:
                    s = ListItemItem.ITEM_TITLE_TYPE_ALL;
                    break;
                case Home.VIEW_TYPE_STARRED:
                    s = ListItemItem.ITEM_TITLE_TYPE_STARRED;
                    break;
                case Home.VIEW_TYPE_UNREAD:
                    s = ListItemItem.ITEM_TITLE_TYPE_UNREAD;
                    break;
                default:
                    s = "";
                }
                s += curDateString;
                lstWrapper.updateTitle(s, curDateString);
                lastDateString = curDateString;
            }
            lstWrapper.updateItem(item);
        }
        cur.close();
        if (count < 20) {
            if (!isDecendingOrdering || (viewType == Home.VIEW_TYPE_STARRED && uid.length() > 0)) {
                lstWrapper.updateItemEndDisabled();
            } else {
                updateLoadMore();
            }
            isEnd = true;
        }
    }

    private void updateLoadMore() {
        if (syncer == null) {
            final SettingSyncMethod sSync = new SettingSyncMethod(dataMgr);
            final int syncingMethod = NetworkUtils.checkSyncingNetworkStatus(context, sSync.getData()) ? sSync
                    .getData() : SettingSyncMethod.SYNC_METHOD_MANUAL;
            final int count = lstWrapper.getAdapter().getCount();
            final long time = (count > 0) ? ((ListItemItem) lstWrapper.getAdapter().getItem(count - 1)).getTimestamp() / 1000000 - 1
                    : 0;
            if (viewType == Home.VIEW_TYPE_STARRED) {
                syncer = ItemDataSyncer.getInstance(dataMgr, syncingMethod, "user/-/state/com.google/starred", time,
                        false);
            } else {
                syncer = ItemDataSyncer.getInstance(dataMgr, syncingMethod, uid, time,
                        (viewType == Home.VIEW_TYPE_UNREAD));
            }
        }
        if (syncer.isEnd()) {
            lstWrapper.updateItemEndDisabled();
        } else if (syncer.isRunning()) {
            lstWrapper.updateItemEndLoading();
        } else {
            final SettingSyncMethod sSync = new SettingSyncMethod(dataMgr);
            final int syncingMethod = NetworkUtils.checkSyncingNetworkStatus(context, sSync.getData()) ? sSync
                    .getData() : SettingSyncMethod.SYNC_METHOD_MANUAL;
            if (syncingMethod != SettingSyncMethod.SYNC_METHOD_MANUAL) {
                NetworkMgr.getInstance().startSync(syncer);
                lstWrapper.updateItemEndLoading();
            } else {
                lstWrapper.updateItemEndEnabled();
            }
        }
    }
}
