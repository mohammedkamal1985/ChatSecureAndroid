/*
 * Copyright (C) 2007-2008 Esmertec AG. Copyright (C) 2007-2008 The Android Open
 * Source Project
 * 
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not
 * use this file except in compliance with the License. You may obtain a copy of
 * the License at
 * 
 * http://www.apache.org/licenses/LICENSE-2.0
 * 
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations under
 * the License.
 */

package info.guardianproject.otr.app.im.service;

import info.guardianproject.otr.IOtrChatSession;
import info.guardianproject.otr.OtrChatListener;
import info.guardianproject.otr.OtrChatManager;
import info.guardianproject.otr.OtrChatSessionAdapter;
import info.guardianproject.otr.OtrDataHandler;
import info.guardianproject.otr.OtrDataHandler.Transfer;
import info.guardianproject.otr.app.im.IChatListener;
import info.guardianproject.otr.app.im.IDataListener;
import info.guardianproject.otr.app.im.engine.Address;
import info.guardianproject.otr.app.im.engine.ChatGroup;
import info.guardianproject.otr.app.im.engine.ChatGroupManager;
import info.guardianproject.otr.app.im.engine.ChatSession;
import info.guardianproject.otr.app.im.engine.Contact;
import info.guardianproject.otr.app.im.engine.DataHandler;
import info.guardianproject.otr.app.im.engine.GroupListener;
import info.guardianproject.otr.app.im.engine.GroupMemberListener;
import info.guardianproject.otr.app.im.engine.ImConnection;
import info.guardianproject.otr.app.im.engine.ImEntity;
import info.guardianproject.otr.app.im.engine.ImErrorInfo;
import info.guardianproject.otr.app.im.engine.Message;
import info.guardianproject.otr.app.im.engine.MessageListener;
import info.guardianproject.otr.app.im.engine.Presence;
import info.guardianproject.otr.app.im.provider.Imps;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;

import org.jivesoftware.smack.packet.Packet;

import android.content.ContentResolver;
import android.content.ContentUris;
import android.content.ContentValues;
import android.database.Cursor;
import android.net.Uri;
import android.os.RemoteCallbackList;
import android.os.RemoteException;
import android.provider.BaseColumns;

import com.google.common.collect.Maps;

public class ChatSessionAdapter extends info.guardianproject.otr.app.im.IChatSession.Stub {

    private static final String NON_CHAT_MESSAGE_SELECTION = Imps.Messages.TYPE + "!="
                                                             + Imps.MessageType.INCOMING + " AND "
                                                             + Imps.Messages.TYPE + "!="
                                                             + Imps.MessageType.OUTGOING;

    /** The registered remote listeners. */
    final RemoteCallbackList<IChatListener> mRemoteListeners = new RemoteCallbackList<IChatListener>();

    ImConnectionAdapter mConnection;
    ChatSessionManagerAdapter mChatSessionManager;

    OtrChatSessionAdapter mOtrChatSession;

    ChatSession mChatSession;
    ListenerAdapter mListenerAdapter;
    boolean mIsGroupChat;
    StatusBarNotifier mStatusBarNotifier;

    private ContentResolver mContentResolver;
    /*package*/Uri mChatURI;

    private Uri mMessageURI;
    private Uri mOtrMessageURI;

    private boolean mConvertingToGroupChat;

    private static final int MAX_HISTORY_COPY_COUNT = 10;

    private HashMap<String, Integer> mContactStatusMap = new HashMap<String, Integer>();

    private boolean mHasUnreadMessages;

    private RemoteImService service = null;
    private DataHandler mDataHandler;

    private IDataListener mDataListener;

    public ChatSessionAdapter(ChatSession chatSession, ImConnectionAdapter connection) {
        
        mChatSession = chatSession;
        mConnection = connection;

        service = connection.getContext();
        mContentResolver = service.getContentResolver();
        mStatusBarNotifier = service.getStatusBarNotifier();
        mChatSessionManager = (ChatSessionManagerAdapter) connection.getChatSessionManager();

        mListenerAdapter = new ListenerAdapter();
        
        initOtrChatSession();//setup first time

        ImEntity participant = mChatSession.getParticipant();

        if (participant instanceof ChatGroup) {
            init((ChatGroup) participant);
        } else {
            init((Contact) participant);
        }
    }

    public void initOtrChatSession ()
    {

        mDataHandler = new OtrDataHandler(mChatSession);
        mOtrChatSession = new OtrChatSessionAdapter(mConnection.getLoginUser().getAddress().getAddress(), mChatSession.getParticipant().getAddress().getAddress(), service.getOtrChatManager());
        // add OtrChatListener as the intermediary to mListenerAdapter so it can filter OTR msgs
        mChatSession.addMessageListener(new OtrChatListener(service.getOtrChatManager(), mListenerAdapter));
        mChatSession.setOtrChatManager(service.getOtrChatManager());
    }

    public IOtrChatSession getOtrChatSession() {

        if (mOtrChatSession == null)
            initOtrChatSession();
        
        return mOtrChatSession;
    }

    private void init(ChatGroup group) {
        mIsGroupChat = true;
        long groupId = insertGroupContactInDb(group);
        group.addMemberListener(mListenerAdapter);

        mMessageURI = Imps.Messages.getContentUriByThreadId(groupId);
        mOtrMessageURI = Imps.Messages.getOtrMessagesContentUriByThreadId(groupId);
        
        mChatURI = ContentUris.withAppendedId(Imps.Chats.CONTENT_URI, groupId);
        insertOrUpdateChat(null);

        for (Contact c : group.getMembers()) {
            mContactStatusMap.put(c.getName(), c.getPresence().getStatus());
        }
    }

    private void init(Contact contact) {
        mIsGroupChat = false;
        ContactListManagerAdapter listManager = (ContactListManagerAdapter) mConnection
                .getContactListManager();
        long contactId = listManager.queryOrInsertContact(contact);

        mMessageURI = Imps.Messages.getContentUriByThreadId(contactId);
        mOtrMessageURI = Imps.Messages.getOtrMessagesContentUriByThreadId(contactId);

        mChatURI = ContentUris.withAppendedId(Imps.Chats.CONTENT_URI, contactId);
        insertOrUpdateChat(null);

        mContactStatusMap.put(contact.getName(), contact.getPresence().getStatus());
    }

    private ChatGroupManager getGroupManager() {
        return mConnection.getAdaptee().getChatGroupManager();
    }

    public ChatSession getAdaptee() {
        return mChatSession;
    }

    public Uri getChatUri() {
        return mChatURI;
    }

    public String[] getParticipants() {
        if (mIsGroupChat) {
            Contact self = mConnection.getLoginUser();
            ChatGroup group = (ChatGroup) mChatSession.getParticipant();
            List<Contact> members = group.getMembers();
            String[] result = new String[members.size() - 1];
            int index = 0;
            for (Contact c : members) {
                if (!c.equals(self)) {
                    result[index++] = c.getAddress().getAddress();
                }
            }
            return result;
        } else {
            
            return new String[] { mChatSession.getParticipant().getAddress().getAddress() };
        }
    }

    /**
     * Convert this chat session to a group chat. If it's already a group chat,
     * nothing will happen. The method works in async mode and the registered
     * listener will be notified when it's converted to group chat successfully.
     * 
     * Note that the method is not thread-safe since it's always called from the
     * UI and Android uses single thread mode for UI.
     */
    public void convertToGroupChat() {
        if (mIsGroupChat || mConvertingToGroupChat) {
            return;
        }

        mConvertingToGroupChat = true;
        new ChatConvertor().convertToGroupChat();
    }

    public boolean isGroupChatSession() {
        return mIsGroupChat;
    }

    public String getName() {
        return mChatSession.getParticipant().getAddress().getUser();
    }

    public String getAddress() {
        return mChatSession.getParticipant().getAddress().getAddress();
    }

    public long getId() {
        return ContentUris.parseId(mChatURI);
    }

    public void inviteContact(String contact) {
        if (!mIsGroupChat) {
            return;
        }
        ContactListManagerAdapter listManager = (ContactListManagerAdapter) mConnection
                .getContactListManager();
        Contact invitee = listManager.getContactByAddress(contact);
        if (invitee == null) {
            ImErrorInfo error = new ImErrorInfo(ImErrorInfo.ILLEGAL_CONTACT_ADDRESS,
                    "Cannot find contact with address: " + contact);
            mListenerAdapter.onError((ChatGroup) mChatSession.getParticipant(), error);
        } else {
            getGroupManager().inviteUserAsync((ChatGroup) mChatSession.getParticipant(), invitee);
        }
    }

    public void leave() {
        if (mIsGroupChat) {
            getGroupManager().leaveChatGroupAsync((ChatGroup) mChatSession.getParticipant());
        }

        mContentResolver.delete(mMessageURI, null, null);
        mContentResolver.delete(mChatURI, null, null);
        mStatusBarNotifier.dismissChatNotification(mConnection.getProviderId(), getAddress());
        mChatSessionManager.closeChatSession(this);
    }

    public void leaveIfInactive() {
        if (mChatSession.getHistoryMessages().isEmpty()) {
            leave();
        }
    }

    public void sendMessage(String text) {
        if (mConnection.getState() == ImConnection.SUSPENDED) {
            // connection has been suspended, save the message without send it
            insertMessageInDb(null, text, -1, Imps.MessageType.POSTPONED);
            return;
        }

        Message msg = new Message(text);
        // TODO OTRCHAT move setFrom() to ChatSession.sendMessageAsync()
        msg.setFrom(mConnection.getLoginUser().getAddress());
        
        msg.setType(Imps.MessageType.OUTGOING);
        
        mChatSession.sendMessageAsync(msg);
        
        long now = System.currentTimeMillis();
        if (!isGroupChatSession())
            insertMessageInDb(null, text, now, msg.getType(), 0, msg.getID());
    }

    public void offerData(String url, String type) {
        if (mConnection.getState() == ImConnection.SUSPENDED) {
            // TODO send later
            return;
        }
        
        HashMap<String, String> headers = null;
        if (type != null) {
            headers = Maps.newHashMap();
            headers.put("Mime-Type", type);
        }
        
        mDataHandler.offerData(mConnection.getLoginUser().getAddress(), url, headers);
    }

    /**
     * Sends a message to other participant(s) in this session without adding it
     * to the history.
     * 
     * @param msg the message to send.
     */
    /*
    public void sendMessageWithoutHistory(String text) {
    
     Message msg = new Message(text);
     // TODO OTRCHAT use a lower level method
     mChatSession.sendMessageAsync(msg);
    }*/

    void sendPostponedMessages() {
        String[] projection = new String[] { BaseColumns._ID, Imps.Messages.BODY,
                                             Imps.Messages.PACKET_ID,
                                            Imps.Messages.DATE, Imps.Messages.TYPE, };
        String selection = Imps.Messages.TYPE + "=?";

        Cursor c = mContentResolver.query(mMessageURI, projection, selection,
                new String[] { Integer.toString(Imps.MessageType.POSTPONED) }, null);
        if (c == null) {
            RemoteImService.debug("Query error while querying postponed messages");
            return;
        }

        while (c.moveToNext()) {
            String body = c.getString(1);
            String id = c.getString(2);
            Message msg = new Message(body);
            // TODO OTRCHAT move setFrom() to ChatSession.sendMessageAsync()
            msg.setFrom(mConnection.getLoginUser().getAddress());
            msg.setID(id);
            msg.setType(Imps.MessageType.OUTGOING);
            
            mChatSession.sendMessageAsync(msg);            
            
            updateMessageInDb(id, msg.getType(), System.currentTimeMillis());

        }
        //c.commitUpdates();
        c.close();
    }

    public void registerChatListener(IChatListener listener) {
        if (listener != null) {
            mRemoteListeners.register(listener);
        }
    }

    public void unregisterChatListener(IChatListener listener) {
        if (listener != null) {
            mRemoteListeners.unregister(listener);
        }
    }

    public void markAsRead() {
        if (mHasUnreadMessages) {
            ContentValues values = new ContentValues(1);
            values.put(Imps.Chats.LAST_UNREAD_MESSAGE, (String) null);
            mConnection.getContext().getContentResolver().update(mChatURI, values, null, null);

            mStatusBarNotifier.dismissChatNotification(mConnection.getProviderId(), getAddress());

            mHasUnreadMessages = false;
        }
    }

    String getNickName(String username) {
        ImEntity participant = mChatSession.getParticipant();
        if (mIsGroupChat) {
            ChatGroup group = (ChatGroup) participant;
            List<Contact> members = group.getMembers();
            for (Contact c : members) {
                if (username.equals(c.getAddress().getAddress())) {
                    return c.getName();
                }
            }
            // not found, impossible
            return username;
        } else {
            return ((Contact) participant).getName();
        }
    }

    void onConvertToGroupChatSuccess(ChatGroup group) {
        Contact oldParticipant = (Contact) mChatSession.getParticipant();
        String oldAddress = getAddress();
        mChatSession.setParticipant(group);
        mChatSessionManager.updateChatSession(oldAddress, this);

        Uri oldChatUri = mChatURI;
        Uri oldMessageUri = mMessageURI;
        init(group);
        copyHistoryMessages(oldParticipant);

        mContentResolver.delete(oldMessageUri, NON_CHAT_MESSAGE_SELECTION, null);
        mContentResolver.delete(oldChatUri, null, null);

        mListenerAdapter.notifyChatSessionConverted();
        mConvertingToGroupChat = false;
    }

    private void copyHistoryMessages(Contact oldParticipant) {
        List<Message> historyMessages = mChatSession.getHistoryMessages();
        int total = historyMessages.size();
        int start = total > MAX_HISTORY_COPY_COUNT ? total - MAX_HISTORY_COPY_COUNT : 0;
        for (int i = start; i < total; i++) {
            Message msg = historyMessages.get(i);
            boolean incoming = msg.getFrom().equals(oldParticipant.getAddress());
            String contact = incoming ? oldParticipant.getName() : null;
            long time = msg.getDateTime().getTime();
            insertMessageInDb(contact, msg.getBody(), time, incoming ? Imps.MessageType.INCOMING
                                                                    : Imps.MessageType.OUTGOING);
        }
    }

    void insertOrUpdateChat(String message) {
        ContentValues values = new ContentValues(2);

        values.put(Imps.Chats.LAST_MESSAGE_DATE, System.currentTimeMillis());
        values.put(Imps.Chats.LAST_UNREAD_MESSAGE, message);
        // ImProvider.insert() will replace the chat if it already exist.
        mContentResolver.insert(mChatURI, values);
    }

    private long insertGroupContactInDb(ChatGroup group) {
        // Insert a record in contacts table
        ContentValues values = new ContentValues(4);
        values.put(Imps.Contacts.USERNAME, group.getAddress().getAddress());
        values.put(Imps.Contacts.NICKNAME, group.getName());
        values.put(Imps.Contacts.CONTACTLIST, ContactListManagerAdapter.FAKE_TEMPORARY_LIST_ID);
        values.put(Imps.Contacts.TYPE, Imps.Contacts.TYPE_GROUP);

        Uri contactUri = ContentUris.withAppendedId(
                ContentUris.withAppendedId(Imps.Contacts.CONTENT_URI, mConnection.mProviderId),
                mConnection.mAccountId);
        long id = ContentUris.parseId(mContentResolver.insert(contactUri, values));

        ArrayList<ContentValues> memberValues = new ArrayList<ContentValues>();
        Contact self = mConnection.getLoginUser();
        for (Contact member : group.getMembers()) {
            if (!member.equals(self)) { // avoid to insert the user himself
                ContentValues memberValue = new ContentValues(2);
                memberValue.put(Imps.GroupMembers.USERNAME, member.getAddress().getAddress());
                memberValue.put(Imps.GroupMembers.NICKNAME, member.getName());
                memberValues.add(memberValue);
            }
        }
        if (!memberValues.isEmpty()) {
            ContentValues[] result = new ContentValues[memberValues.size()];
            memberValues.toArray(result);
            Uri memberUri = ContentUris.withAppendedId(Imps.GroupMembers.CONTENT_URI, id);
            mContentResolver.bulkInsert(memberUri, result);
        }
        return id;
    }

    void insertGroupMemberInDb(Contact member) {
        ContentValues values1 = new ContentValues(2);
        values1.put(Imps.GroupMembers.USERNAME, member.getAddress().getAddress());
        values1.put(Imps.GroupMembers.NICKNAME, member.getName());
        ContentValues values = values1;

        long groupId = ContentUris.parseId(mChatURI);
        Uri uri = ContentUris.withAppendedId(Imps.GroupMembers.CONTENT_URI, groupId);
        mContentResolver.insert(uri, values);

        insertMessageInDb(member.getName(), null, System.currentTimeMillis(),
                Imps.MessageType.PRESENCE_AVAILABLE);
    }

    void deleteGroupMemberInDb(Contact member) {
        String where = Imps.GroupMembers.USERNAME + "=?";
        String[] selectionArgs = { member.getAddress().getAddress() };
        long groupId = ContentUris.parseId(mChatURI);
        Uri uri = ContentUris.withAppendedId(Imps.GroupMembers.CONTENT_URI, groupId);
        mContentResolver.delete(uri, where, selectionArgs);

        insertMessageInDb(member.getName(), null, System.currentTimeMillis(),
                Imps.MessageType.PRESENCE_UNAVAILABLE);
    }

    void insertPresenceUpdatesMsg(String contact, Presence presence) {
        int status = presence.getStatus();

        Integer previousStatus = mContactStatusMap.get(contact);
        if (previousStatus != null && previousStatus == status) {
            // don't insert the presence message if it's the same status
            // with the previous presence update notification
            return;
        }

        mContactStatusMap.put(contact, status);
        int messageType;
        switch (status) {
        case Presence.AVAILABLE:
            messageType = Imps.MessageType.PRESENCE_AVAILABLE;
            break;

        case Presence.AWAY:
        case Presence.IDLE:
            messageType = Imps.MessageType.PRESENCE_AWAY;
            break;

        case Presence.DO_NOT_DISTURB:
            messageType = Imps.MessageType.PRESENCE_DND;
            break;

        default:
            messageType = Imps.MessageType.PRESENCE_UNAVAILABLE;
            break;
        }

        if (mIsGroupChat) {
            insertMessageInDb(contact, null, System.currentTimeMillis(), messageType);
        } else {
            insertMessageInDb(null, null, System.currentTimeMillis(), messageType);
        }
    }

    void removeMessageInDb(int type) {
        mContentResolver.delete(mMessageURI, Imps.Messages.TYPE + "=?",
                new String[] { Integer.toString(type) });
    }

    Uri insertMessageInDb(String contact, String body, long time, int type) {
        return insertMessageInDb(contact, body, time, type, 0/*No error*/, Packet.nextID());
    }

    Uri insertMessageInDb(String contact, String body, long time, int type, int errCode, String id) {
        
        ContentValues values = new ContentValues(mIsGroupChat ? 4 : 3);
        values.put(Imps.Messages.BODY, body);
        values.put(Imps.Messages.DATE, time);
        values.put(Imps.Messages.TYPE, type);
        values.put(Imps.Messages.ERROR_CODE, errCode);
        if (mIsGroupChat) {
            values.put(Imps.Messages.NICKNAME, contact);
            values.put(Imps.Messages.IS_GROUP_CHAT, 1);
        }
        values.put(Imps.Messages.IS_DELIVERED, 0);
        values.put(Imps.Messages.PACKET_ID, id);

        boolean isEncrypted = true;
        try {
            isEncrypted = mOtrChatSession.isChatEncrypted();
        } catch (RemoteException e) {
            // Leave it as encrypted so it gets stored in memory
            // TODO(miron)
        }
        
        return mContentResolver.insert(isEncrypted ? mOtrMessageURI : mMessageURI, values);
    }

    int updateConfirmInDb(String id, int value) {
        Uri.Builder builder = Imps.Messages.OTR_MESSAGES_CONTENT_URI_BY_PACKET_ID.buildUpon();
        builder.appendPath(id);
        
        ContentValues values = new ContentValues(1);
        values.put(Imps.Messages.IS_DELIVERED, value);
        return mContentResolver.update(builder.build(), values, null, null);
    }

    int updateMessageInDb(String id, int type, long time) {
        
        Uri.Builder builder = Imps.Messages.OTR_MESSAGES_CONTENT_URI_BY_PACKET_ID.buildUpon();
        builder.appendPath(id);
        
        ContentValues values = new ContentValues(1);
        values.put(Imps.Messages.TYPE, type);
        values.put(Imps.Messages.DATE, time);
        return mContentResolver.update(builder.build(), values, null, null);
    }

    
    
    class ListenerAdapter implements MessageListener, GroupMemberListener {

        public boolean  onIncomingMessage(ChatSession ses, final Message msg) {
            String body = msg.getBody();
            String username = msg.getFrom().getAddress();
            String bareUsername = msg.getFrom().getBareAddress();
            String nickname = getNickName(username);
            long time = msg.getDateTime().getTime();
            
            insertOrUpdateChat(body);
            
            insertMessageInDb(username, body, time, msg.getType());

            boolean wasMessageSeen = false;
            
            int N = mRemoteListeners.beginBroadcast();
            for (int i = 0; i < N; i++) {
                IChatListener listener = mRemoteListeners.getBroadcastItem(i);
                try {
                    boolean wasSeen = listener.onIncomingMessage(ChatSessionAdapter.this, msg);
                    
                    if (wasSeen)
                        wasMessageSeen = wasSeen;
                    
                } catch (RemoteException e) {
                    // The RemoteCallbackList will take care of removing the
                    // dead listeners.
                }
            }
            mRemoteListeners.finishBroadcast();

            // Due to the move to fragments, we could have listeners for ChatViews that are not visible on the screen.
            // This is for fragments adjacent to the current one.  Therefore we can't use the existence of listeners
            // as a filter on notifications.
            if (!wasMessageSeen)
            {
                //reinstated body display here in the notification; perhaps add preferences to turn that off
                mStatusBarNotifier.notifyChat(mConnection.getProviderId(), mConnection.getAccountId(),
                        getId(), bareUsername, nickname, body, false);
            }
            
            mHasUnreadMessages = true;
            return true;
        }

        public void onSendMessageError(ChatSession ses, final Message msg, final ImErrorInfo error) {
            insertMessageInDb(null, null, System.currentTimeMillis(), Imps.MessageType.OUTGOING,
                    error.getCode(), null);

            final int N = mRemoteListeners.beginBroadcast();
            for (int i = 0; i < N; i++) {
                IChatListener listener = mRemoteListeners.getBroadcastItem(i);
                try {
                    listener.onSendMessageError(ChatSessionAdapter.this, msg, error);
                } catch (RemoteException e) {
                    // The RemoteCallbackList will take care of removing the
                    // dead listeners.
                }
            }
            mRemoteListeners.finishBroadcast();
        }

        public void onMemberJoined(ChatGroup group, final Contact contact) {
            insertGroupMemberInDb(contact);

            final int N = mRemoteListeners.beginBroadcast();
            for (int i = 0; i < N; i++) {
                IChatListener listener = mRemoteListeners.getBroadcastItem(i);
                try {
                    listener.onContactJoined(ChatSessionAdapter.this, contact);
                } catch (RemoteException e) {
                    // The RemoteCallbackList will take care of removing the
                    // dead listeners.
                }
            }
            mRemoteListeners.finishBroadcast();
        }

        public void onMemberLeft(ChatGroup group, final Contact contact) {
            deleteGroupMemberInDb(contact);

            final int N = mRemoteListeners.beginBroadcast();
            for (int i = 0; i < N; i++) {
                IChatListener listener = mRemoteListeners.getBroadcastItem(i);
                try {
                    listener.onContactLeft(ChatSessionAdapter.this, contact);
                } catch (RemoteException e) {
                    // The RemoteCallbackList will take care of removing the
                    // dead listeners.
                }
            }
            mRemoteListeners.finishBroadcast();
        }

        public void onError(ChatGroup group, final ImErrorInfo error) {
            // TODO: insert an error message?
            final int N = mRemoteListeners.beginBroadcast();
            for (int i = 0; i < N; i++) {
                IChatListener listener = mRemoteListeners.getBroadcastItem(i);
                try {
                    listener.onInviteError(ChatSessionAdapter.this, error);
                } catch (RemoteException e) {
                    // The RemoteCallbackList will take care of removing the
                    // dead listeners.
                }
            }
            mRemoteListeners.finishBroadcast();
        }

        public void notifyChatSessionConverted() {
            final int N = mRemoteListeners.beginBroadcast();
            for (int i = 0; i < N; i++) {
                IChatListener listener = mRemoteListeners.getBroadcastItem(i);
                try {
                    listener.onConvertedToGroupChat(ChatSessionAdapter.this);
                } catch (RemoteException e) {
                    // The RemoteCallbackList will take care of removing the
                    // dead listeners.
                }
            }
            mRemoteListeners.finishBroadcast();
        }

        @Override
        public void onIncomingReceipt(ChatSession ses, String id) {
            updateConfirmInDb(id, 1);

            int N = mRemoteListeners.beginBroadcast();
            for (int i = 0; i < N; i++) {
                IChatListener listener = mRemoteListeners.getBroadcastItem(i);
                try {
                    listener.onIncomingReceipt(ChatSessionAdapter.this, id);
                } catch (RemoteException e) {
                    // The RemoteCallbackList will take care of removing the
                    // dead listeners.
                }
            }
            mRemoteListeners.finishBroadcast();
        }

        @Override
        public void onMessagePostponed(ChatSession ses, String id) {
            updateMessageInDb(id, Imps.MessageType.POSTPONED, -1);
        }

        @Override
        public void onReceiptsExpected(ChatSession ses) {
            // TODO

        }

        @Override
        public void onStatusChanged(ChatSession session) {
            final int N = mRemoteListeners.beginBroadcast();
            for (int i = 0; i < N; i++) {
                IChatListener listener = mRemoteListeners.getBroadcastItem(i);
                try {
                    listener.onStatusChanged(ChatSessionAdapter.this);
                } catch (RemoteException e) {
                    // The RemoteCallbackList will take care of removing the
                    // dead listeners.   // TODO Auto-generated method stub
                }
            }
            mRemoteListeners.finishBroadcast();
        }

        @Override
        public void onIncomingDataRequest(ChatSession session, Message msg, byte[] value) {
            mDataHandler.onIncomingRequest(msg.getFrom(),msg.getTo(), value);
        }

        @Override
        public void onIncomingDataResponse(ChatSession session, Message msg, byte[] value) {
            mDataHandler.onIncomingResponse(msg.getFrom(),msg.getTo(), value);
        }

        @Override
        public void onIncomingTransferRequest(final Transfer transfer) {
            
        }
    }

    class ChatConvertor implements GroupListener, GroupMemberListener {
        private ChatGroupManager mGroupMgr;
        private String mGroupName;

        public ChatConvertor() {
            mGroupMgr = mConnection.mGroupManager;
        }

        public void convertToGroupChat() {
            mGroupMgr.addGroupListener(this);
            mGroupName = "G" + System.currentTimeMillis();
            try
            {
                mGroupMgr.createChatGroupAsync(mGroupName);
            }
            catch (Exception e){
                e.printStackTrace();
            }
        }

        public void onGroupCreated(ChatGroup group) {
            if (mGroupName.equalsIgnoreCase(group.getName())) {
                mGroupMgr.removeGroupListener(this);
                group.addMemberListener(this);
                mGroupMgr.inviteUserAsync(group, (Contact) mChatSession.getParticipant());
            }
        }

        public void onMemberJoined(ChatGroup group, Contact contact) {
            if (mChatSession.getParticipant().equals(contact)) {
                onConvertToGroupChatSuccess(group);
            }

            mContactStatusMap.put(contact.getName(), contact.getPresence().getStatus());
        }

        public void onGroupDeleted(ChatGroup group) {
        }

        public void onGroupError(int errorType, String groupName, ImErrorInfo error) {
        }

        public void onJoinedGroup(ChatGroup group) {
        }

        public void onLeftGroup(ChatGroup group) {
        }

        public void onError(ChatGroup group, ImErrorInfo error) {
        }

        public void onMemberLeft(ChatGroup group, Contact contact) {
            mContactStatusMap.remove(contact.getName());
        }
    }

    @Override
    public void setDataListener(IDataListener dataListener) throws RemoteException {
        
        mDataListener = dataListener;
        mDataHandler.setDataListener(mDataListener);
    }
}
