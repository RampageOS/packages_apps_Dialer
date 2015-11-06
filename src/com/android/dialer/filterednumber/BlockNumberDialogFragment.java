/*
 * Copyright (C) 2015 The Android Open Source Project
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
 * limitations under the License
 */

package com.android.dialer.filterednumber;

import android.app.AlertDialog;
import android.app.Dialog;
import android.app.DialogFragment;
import android.app.FragmentManager;
import android.content.ContentValues;
import android.content.Context;
import android.content.DialogInterface;
import android.net.Uri;
import android.os.Bundle;
import android.support.design.widget.Snackbar;
import android.text.TextUtils;
import android.view.View;
import android.widget.Toast;

import com.android.contacts.common.util.ContactDisplayUtils;
import com.android.dialer.R;
import com.android.dialer.database.FilteredNumberAsyncQueryHandler;
import com.android.dialer.database.FilteredNumberAsyncQueryHandler.OnBlockNumberListener;
import com.android.dialer.database.FilteredNumberAsyncQueryHandler.OnUnblockNumberListener;
import com.android.dialer.voicemail.VisualVoicemailEnabledChecker;

/**
 * Fragment for confirming and enacting blocking/unblocking a number. Also invokes snackbar
 * providing undo functionality.
 */
public class BlockNumberDialogFragment extends DialogFragment
        implements VisualVoicemailEnabledChecker.Callback{

    /**
     * Use a callback interface to update UI after success/undo. Favor this approach over other
     * more standard paradigms because of the variety of scenarios in which the DialogFragment
     * can be invoked (by an Activity, by a fragment, by an adapter, by an adapter list item).
     * Because of this, we do NOT support retaining state on rotation, and will dismiss the dialog
     * upon rotation instead.
     */
    public interface Callback {
        public void onChangeFilteredNumberSuccess();
        public void onChangeFilteredNumberUndo();
    }

    private static final String BLOCK_DIALOG_FRAGMENT = "BlockNumberDialog";

    private static final String ARG_BLOCK_ID = "argBlockId";
    private static final String ARG_NUMBER = "argNumber";
    private static final String ARG_COUNTRY_ISO = "argCountryIso";
    private static final String ARG_DISPLAY_NUMBER = "argDisplayNumber";
    private static final String ARG_PARENT_VIEW_ID = "parentViewId";

    private String mNumber;
    private String mDisplayNumber;
    private String mCountryIso;

    private FilteredNumberAsyncQueryHandler mHandler;
    private View mParentView;
    private VisualVoicemailEnabledChecker mVoicemailEnabledChecker;
    private Callback mCallback;
    private AlertDialog mAlertDialog;

    public static void show(
            Context context,
            Integer blockId,
            String number,
            String countryIso,
            String displayNumber,
            Integer parentViewId,
            FragmentManager fragmentManager,
            Callback callback) {
        final BlockNumberDialogFragment newFragment = BlockNumberDialogFragment.newInstance(
                context, blockId, number, countryIso, displayNumber, parentViewId);

        newFragment.setCallback(callback);
        newFragment.show(fragmentManager, BlockNumberDialogFragment.BLOCK_DIALOG_FRAGMENT);
    }

    private static BlockNumberDialogFragment newInstance(
            Context context,
            Integer blockId,
            String number,
            String countryIso,
            String displayNumber,
            Integer parentViewId) {
        final BlockNumberDialogFragment fragment = new BlockNumberDialogFragment();
        final Bundle args = new Bundle();
        if (blockId != null) {
            args.putInt(ARG_BLOCK_ID, blockId.intValue());
        }
        if (parentViewId != null) {
            args.putInt(ARG_PARENT_VIEW_ID, parentViewId.intValue());
        }
        args.putString(ARG_NUMBER, number);
        args.putString(ARG_COUNTRY_ISO, countryIso);
        args.putString(ARG_DISPLAY_NUMBER, displayNumber);
        fragment.setArguments(args);
        fragment.mVoicemailEnabledChecker = new VisualVoicemailEnabledChecker(context,fragment);
        fragment.mVoicemailEnabledChecker.asyncUpdate();
        return fragment;
    }

    @Override
    public Dialog onCreateDialog(Bundle savedInstanceState) {
        super.onCreateDialog(savedInstanceState);
        final boolean isBlocked = getArguments().containsKey(ARG_BLOCK_ID);

        mNumber = getArguments().getString(ARG_NUMBER);
        mDisplayNumber = getArguments().getString(ARG_DISPLAY_NUMBER);
        mCountryIso = getArguments().getString(ARG_COUNTRY_ISO);

        if (TextUtils.isEmpty(mDisplayNumber)) {
            mDisplayNumber = mNumber;
        }

        mHandler = new FilteredNumberAsyncQueryHandler(getContext().getContentResolver());
        mParentView = getActivity().findViewById(getArguments().getInt(ARG_PARENT_VIEW_ID));

        CharSequence title;
        String okText;
        String message;
        if (isBlocked) {
            title = getTtsSpannedPhoneNumberString(R.string.unblock_number_confirmation_title,
                mDisplayNumber);
            okText = getString(R.string.unblock_number_ok);
            message = null;
        } else {
            title = getTtsSpannedPhoneNumberString(R.string.block_number_confirmation_title,
                mDisplayNumber);
            okText = getString(R.string.block_number_ok);
            if (mVoicemailEnabledChecker.isVisualVoicemailEnabled()) {
                message = getString(R.string.block_number_confirmation_message_vvm);
            } else {
                message = getString(R.string.block_number_confirmation_message_no_vvm);
            }
        }


        AlertDialog.Builder builder = new AlertDialog.Builder(getActivity())
                .setTitle(title)
                .setMessage(message)
                .setPositiveButton(okText, new DialogInterface.OnClickListener() {
                    public void onClick(DialogInterface dialog, int id) {
                        if (isBlocked) {
                            unblockNumber();
                        } else {
                            blockNumber();
                        }
                        mAlertDialog = null;
                    }
                })
                .setNegativeButton(android.R.string.cancel,  new DialogInterface.OnClickListener() {
                    public void onClick(DialogInterface dialog, int id) {
                        mAlertDialog = null;
                    }
                });
        mAlertDialog = builder.create();
        return mAlertDialog;
    }

    @Override
    public void onActivityCreated(Bundle savedInstanceState) {
        super.onActivityCreated(savedInstanceState);
        if (!FilteredNumbersUtil.canBlockNumber(getActivity(), mNumber, mCountryIso)) {
            dismiss();
            Toast.makeText(getContext(), getString(R.string.invalidNumber, mDisplayNumber),
                    Toast.LENGTH_SHORT).show();
        }
    }

    @Override
    public void onPause() {
        // Dismiss on rotation.
        dismiss();
        mCallback = null;

        super.onPause();
    }

    public void setCallback(Callback callback) {
        mCallback = callback;
    }

    private CharSequence getTtsSpannedPhoneNumberString(int id,String number){
        String msg = getString(id, mDisplayNumber);
        return ContactDisplayUtils.getTelephoneTtsSpannable(msg,mDisplayNumber);
    }

    private CharSequence getBlockedMessage() {
        return getTtsSpannedPhoneNumberString(R.string.snackbar_number_blocked, mDisplayNumber);
    }

    private CharSequence getUnblockedMessage() {
        return getTtsSpannedPhoneNumberString(R.string.snackbar_number_unblocked, mDisplayNumber);
    }

    private int getActionTextColor() {
        return getContext().getResources().getColor(R.color.dialer_snackbar_action_text_color);
    }

    private void blockNumber() {
        final CharSequence message = getBlockedMessage();
        final CharSequence undoMessage = getUnblockedMessage();
        final Callback callback = mCallback;
        final int actionTextColor = getActionTextColor();
        final Context context = getContext();

        final OnUnblockNumberListener onUndoListener = new OnUnblockNumberListener() {
            @Override
            public void onUnblockComplete(int rows, ContentValues values) {
                Snackbar.make(mParentView, undoMessage, Snackbar.LENGTH_LONG).show();
                if (callback != null) {
                    callback.onChangeFilteredNumberUndo();
                }
            }
        };

        final OnBlockNumberListener onBlockNumberListener = new OnBlockNumberListener() {
            @Override
            public void onBlockComplete(final Uri uri) {
                final View.OnClickListener undoListener = new View.OnClickListener() {
                    @Override
                    public void onClick(View view) {
                        // Delete the newly created row on 'undo'.
                        mHandler.unblock(onUndoListener, uri);
                    }
                };

                Snackbar.make(mParentView, message, Snackbar.LENGTH_LONG)
                        .setAction(R.string.block_number_undo, undoListener)
                        .setActionTextColor(actionTextColor)
                        .show();

                if (callback != null) {
                    callback.onChangeFilteredNumberSuccess();
                }

                if (context != null && FilteredNumbersUtil.hasRecentEmergencyCall(context)) {
                    FilteredNumbersUtil.maybeNotifyCallBlockingDisabled(context);
                }
            }
        };

        mHandler.blockNumber(
                onBlockNumberListener,
                mNumber,
                mCountryIso);
    }

    private void unblockNumber() {
        final CharSequence message = getUnblockedMessage();
        final CharSequence undoMessage = getBlockedMessage();
        final Callback callback = mCallback;
        final int actionTextColor = getActionTextColor();

        final OnBlockNumberListener onUndoListener = new OnBlockNumberListener() {
            @Override
            public void onBlockComplete(final Uri uri) {
                Snackbar.make(mParentView, undoMessage, Snackbar.LENGTH_LONG).show();
                if (callback != null) {
                    callback.onChangeFilteredNumberUndo();
                }
            }
        };

        mHandler.unblock(new OnUnblockNumberListener() {
            @Override
            public void onUnblockComplete(int rows, final ContentValues values) {
                final View.OnClickListener undoListener = new View.OnClickListener() {
                    @Override
                    public void onClick(View view) {
                        // Re-insert the row on 'undo', with a new ID.
                        mHandler.blockNumber(onUndoListener, values);
                    }
                };

                Snackbar.make(mParentView, message, Snackbar.LENGTH_LONG)
                        .setAction(R.string.block_number_undo, undoListener)
                        .setActionTextColor(actionTextColor)
                        .show();

                if (callback != null) {
                    callback.onChangeFilteredNumberSuccess();
                }
            }
        }, getArguments().getInt(ARG_BLOCK_ID));
    }

    @Override
    public void onVisualVoicemailEnabledStatusChanged(boolean newStatus){
        updateActiveVoicemailProvider();
    }

    private void updateActiveVoicemailProvider(){
        if(mAlertDialog != null) {
            if (mVoicemailEnabledChecker.isVisualVoicemailEnabled()) {
                mAlertDialog.setMessage(getString(R.string.block_number_confirmation_message_vvm));
            } else {
                mAlertDialog.setMessage(getString(R.string.block_number_confirmation_message_no_vvm));
            }
        }
    }
}