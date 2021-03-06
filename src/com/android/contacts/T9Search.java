/*
 * Copyright (C) 2011 The CyanogenMod Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.android.contacts;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.Set;

import android.content.ContentUris;
import android.content.Context;
import android.graphics.Color;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.net.Uri;
import android.preference.PreferenceManager;
import android.provider.ContactsContract.CommonDataKinds.Phone;
import android.provider.ContactsContract.Contacts;
import android.telephony.PhoneNumberUtils;
import android.text.Spannable;
import android.text.style.ForegroundColorSpan;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ArrayAdapter;
import android.widget.QuickContactBadge;
import android.widget.TextView;

/**
 * @author shade, Danesh, pawitp
 */
class T9Search {

    // List sort modes
    private static final int NAME_FIRST = 1;
    private static final int NUMBER_FIRST = 2;

    // Phone number queries
    private static final String[] PHONE_PROJECTION = new String[] {Phone.NUMBER, Phone.CONTACT_ID, Phone.IS_SUPER_PRIMARY, Phone.TYPE, Phone.LABEL};
    private static final String PHONE_ID_SELECTION = Contacts.Data.MIMETYPE + " = ? ";
    private static final String[] PHONE_ID_SELECTION_ARGS = new String[] {Phone.CONTENT_ITEM_TYPE};
    private static final String PHONE_SORT = Phone.CONTACT_ID + " ASC";
    private static final String[] CONTACT_PROJECTION = new String[] {Contacts._ID, Contacts.DISPLAY_NAME, Contacts.TIMES_CONTACTED};
    private static final String CONTACT_QUERY = Contacts.HAS_PHONE_NUMBER + " > 0";
    private static final String CONTACT_SORT = Contacts._ID + " ASC";

    // Local variables
    private Context mContext;
    private int mSortMode;
    private ArrayList<ContactItem> mNameResults = new ArrayList<ContactItem>();
    private ArrayList<ContactItem> mNumberResults = new ArrayList<ContactItem>();
    private Set<ContactItem> mAllResults = new LinkedHashSet<ContactItem>();
    private ArrayList<ContactItem> mContacts = new ArrayList<ContactItem>();
    private String mPrevInput;
    private static String sT9Chars;
    private static String sT9Digits;

    public T9Search(Context context) {
        mContext = context;
        getAll();
    }

    private void getAll() {
        initT9Map();

        Cursor contact = mContext.getContentResolver().query(Contacts.CONTENT_URI, CONTACT_PROJECTION, CONTACT_QUERY, null, CONTACT_SORT);
        Cursor phone = mContext.getContentResolver().query(Phone.CONTENT_URI, PHONE_PROJECTION, PHONE_ID_SELECTION, PHONE_ID_SELECTION_ARGS, PHONE_SORT);
        phone.moveToFirst();

        while (contact.moveToNext()) {
            long contactId = contact.getLong(0);
            if (phone.isAfterLast()) {
                break;
            }
            while (phone.getLong(1) == contactId) {
                String num = phone.getString(0);
                ContactItem contactInfo = new BitmapContactItem();
                contactInfo.id = contactId;
                contactInfo.name = contact.getString(1);
                contactInfo.number = PhoneNumberUtils.formatNumber(num);
                contactInfo.normalNumber = removeNonDigits(num);
                contactInfo.normalName = nameToNumber(contact.getString(1));
                contactInfo.timesContacted = contact.getInt(2);
                contactInfo.isSuperPrimary = phone.getInt(2) > 0;
                contactInfo.groupType = Phone.getTypeLabel(mContext.getResources(), phone.getInt(3), phone.getString(4));
                mContacts.add(contactInfo);
                if (!phone.moveToNext()) {
                    break;
                }
            }
        }
        contact.close();
        phone.close();
    }

    public static class T9SearchResult {

        private final ArrayList<ContactItem> mResults;
        private final ContactItem mTopContact;

        public T9SearchResult (final ArrayList<ContactItem> results, final Context mContext) {
            mTopContact = results.get(0);
            mResults = results;
            mResults.remove(0);
        }

        public int getNumResults() {
            return mResults.size() + 1;
        }

        public ContactItem getTopContact() {
            return mTopContact;
        }

        public ArrayList<ContactItem> getResults() {
            return mResults;
        }
    }

    public static class ContactItem {
        String name;
        String number;
        String normalNumber;
        String normalName;
        int timesContacted;
        int nameMatchId;
        int numberMatchId;
        CharSequence groupType;
        long id;
        boolean isSuperPrimary;
        public Bitmap getPhoto() {
            return null;
        }
    }

    public class BitmapContactItem extends ContactItem {
        @Override
        public Bitmap getPhoto() {
            Bitmap result = null;
            Uri contactUri = ContentUris.withAppendedId(Contacts.CONTENT_URI, this.id);
            InputStream photoStream = Contacts.openContactPhotoInputStream(mContext.getContentResolver(), contactUri);
            if (photoStream != null) {
                result = BitmapFactory.decodeStream(photoStream);
                try {
                    photoStream.close();
                } catch (IOException e) {
                }
            }
            return result;
        }
    }

    public T9SearchResult search(String number) {
        mNameResults.clear();
        mNumberResults.clear();
        number = removeNonDigits(number);
        int pos = 0;
        mSortMode = Integer.parseInt(PreferenceManager.getDefaultSharedPreferences(mContext).getString("t9_sort", "1"));
        boolean newQuery = mPrevInput == null || number.length() <= mPrevInput.length();
        // Go through each contact
        for (ContactItem item : (newQuery ? mContacts : mAllResults)) {
            item.numberMatchId = -1;
            item.nameMatchId = -1;
            pos = item.normalNumber.indexOf(number);
            if (pos != -1) {
                item.numberMatchId = pos;
                mNumberResults.add(item);
            }
            pos = item.normalName.indexOf(number);
            if (pos != -1) {
                int last_space = item.normalName.lastIndexOf("0", pos);
                if (last_space == -1) {
                    last_space = 0;
                }
                item.nameMatchId = pos - last_space;
                mNameResults.add(item);
            }
        }
        mAllResults.clear();
        mPrevInput = number;
        Collections.sort(mNumberResults, new NumberComparator());
        Collections.sort(mNameResults, new NameComparator());
        if (mNameResults.size() > 0 || mNumberResults.size() > 0) {
            switch (mSortMode) {
            case NAME_FIRST:
                mAllResults.addAll(mNameResults);
                mAllResults.addAll(mNumberResults);
                break;
            case NUMBER_FIRST:
                mAllResults.addAll(mNumberResults);
                mAllResults.addAll(mNameResults);
            }
            return new T9SearchResult(new ArrayList<ContactItem>(mAllResults), mContext);
        }
        return null;
    }

    public static class NameComparator implements Comparator<ContactItem> {
        @Override
        public int compare(ContactItem lhs, ContactItem rhs) {
            int ret = compareInt(lhs.nameMatchId, rhs.nameMatchId);
            if (ret == 0) ret = compareInt(rhs.timesContacted, lhs.timesContacted);
            if (ret == 0) ret = compareBool(rhs.isSuperPrimary, lhs.isSuperPrimary);
            return ret;
        }
    }

    public static class NumberComparator implements Comparator<ContactItem> {
        @Override
        public int compare(ContactItem lhs, ContactItem rhs) {
            int ret = compareInt(lhs.numberMatchId, rhs.numberMatchId);
            if (ret == 0) ret = compareInt(rhs.timesContacted, lhs.timesContacted);
            if (ret == 0) ret = compareBool(rhs.isSuperPrimary, lhs.isSuperPrimary);
            return ret;
        }
    }

    public static int compareInt (int lhs, int rhs) {
        return lhs < rhs ? -1 : (lhs == rhs ? 0 : 1);
    }

    public static int compareBool (boolean lhs, boolean rhs) {
        return lhs == rhs ? 0 : lhs ? 1 : -1;
    }

    private void initT9Map() {
        synchronized (this.getClass()) {
            if (sT9Chars != null)
                return;

            StringBuilder bT9Chars = new StringBuilder();
            StringBuilder bT9Digits = new StringBuilder();
            for (String item: mContext.getResources().getStringArray(R.array.t9_map)) {
                bT9Chars.append(item);
                for (int i = 0; i < item.length(); i++) {
                    bT9Digits.append(item.charAt(0));
                }
            }

            sT9Chars = bT9Chars.toString();
            sT9Digits = bT9Digits.toString();
        }
    }

    private static String nameToNumber(final String name) {
        int len = name.length();
        StringBuilder sb = new StringBuilder(len);
        for (int i = 0; i < len; i++) {
            int pos = sT9Chars.indexOf(Character.toLowerCase(name.charAt(i)));
            if (pos == -1) {
                pos = 0;
            }
            sb.append(sT9Digits.charAt(pos));
        }
        return sb.toString();
    }

    public static String removeNonDigits(final String number) {
        int len = number.length();
        StringBuilder sb = new StringBuilder(len);
        for (int i = 0; i < len; i++) {
            char ch = number.charAt(i);
            if ((ch >= '0' && ch <= '9') || ch == '*' || ch == '#' || ch == '+') {
                sb.append(ch);
            }
        }
        return sb.toString();
    }

    protected class T9Adapter extends ArrayAdapter<ContactItem> {

        private ArrayList<ContactItem> mItems;
        private LayoutInflater mMenuInflate;
        //private ContactPhotoManager mPhotoLoader;

        public T9Adapter(Context context, int textViewResourceId, ArrayList<ContactItem> items, LayoutInflater menuInflate) {
            super(context, textViewResourceId, items);
            mItems = items;
            mMenuInflate = menuInflate;
            //mPhotoLoader = photoLoader;
        }

        @Override
        public View getView(int position, View convertView, ViewGroup parent) {
            ViewHolder holder;

            if (convertView == null) {
                convertView = mMenuInflate.inflate(R.layout.row, null);
                holder = new ViewHolder();
                holder.name = (TextView) convertView.findViewById(R.id.rowName);
                holder.number = (TextView) convertView.findViewById(R.id.rowNumber);
                holder.icon = (QuickContactBadge) convertView.findViewById(R.id.rowBadge);
                convertView.setTag(holder);
            } else {
                holder = (ViewHolder) convertView.getTag();
            }

            ContactItem o = mItems.get(position);
            if (o.name == null) {
                holder.name.setText(mContext.getResources().getString(R.string.t9_add_to_contacts));
                holder.number.setVisibility(View.GONE);
                holder.icon.setImageResource(R.drawable.sym_action_add);
                holder.icon.assignContactFromPhone(o.number, true);
            } else {
                holder.name.setText(o.name, TextView.BufferType.SPANNABLE);
                holder.number.setText(o.normalNumber + " (" + o.groupType + ")", TextView.BufferType.SPANNABLE);
                holder.number.setVisibility(View.VISIBLE);
                if (o.nameMatchId != -1) {
                    Spannable s = (Spannable) holder.name.getText();
                    int nameStart = o.normalName.indexOf(mPrevInput);
                    s.setSpan(new ForegroundColorSpan(Color.WHITE),
                            nameStart, nameStart + mPrevInput.length(), Spannable.SPAN_INCLUSIVE_INCLUSIVE);
                    holder.name.setText(s);
                }
                if (o.numberMatchId != -1) {
                    Spannable s = (Spannable) holder.number.getText();
                    int numberStart = o.numberMatchId;
                    s.setSpan(new ForegroundColorSpan(Color.WHITE),
                            numberStart, numberStart + mPrevInput.length(), Spannable.SPAN_INCLUSIVE_INCLUSIVE);
                    holder.number.setText(s);
                }
                Bitmap photo = o.getPhoto();
                if (photo != null)
                    holder.icon.setImageBitmap(photo);
                else
                    holder.icon.setImageResource(R.drawable.ic_contact_list_picture);

                holder.icon.assignContactFromPhone(o.number, true);
            }
            return convertView;
        }

        class ViewHolder {
            TextView name;
            TextView number;
            QuickContactBadge icon;
        }

    }

}
