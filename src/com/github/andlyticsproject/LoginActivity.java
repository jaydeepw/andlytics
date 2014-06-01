package com.github.andlyticsproject;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

import android.accounts.Account;
import android.accounts.AccountManager;
import android.accounts.AccountManagerCallback;
import android.accounts.AccountManagerFuture;
import android.accounts.AuthenticatorException;
import android.accounts.OperationCanceledException;
import android.content.Context;
import android.content.Intent;
import android.os.AsyncTask;
import android.os.Bundle;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.View.OnClickListener;
import android.view.ViewGroup;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.CheckBox;
import android.widget.CompoundButton;
import android.widget.CompoundButton.OnCheckedChangeListener;
import android.widget.ListView;
import android.widget.TextView;

import com.actionbarsherlock.app.SherlockActivity;
import com.actionbarsherlock.view.Menu;
import com.actionbarsherlock.view.MenuItem;
import com.actionbarsherlock.view.Window;
import com.github.andlyticsproject.model.DeveloperAccount;
import com.github.andlyticsproject.sync.AutosyncHandler;

/**
 * Used for initial login and managing accounts Because of this original legacy as the launcher
 * activity, navigation is a little odd.
 * On first startup: LoginActivity -> Main
 * When managing
 * accounts: Main -> LoginActivity <- Main
 * or
 * Main -> LoginActivity -> Main
 */
public class LoginActivity extends SherlockActivity {

	private static final String TAG = LoginActivity.class.getSimpleName();

	public static final String EXTRA_MANAGE_ACCOUNTS_MODE = "com.github.andlyticsproject.manageAccounts";

	public static final String AUTH_TOKEN_TYPE_ANDROID_DEVELOPER = "androiddeveloper";

	protected static final int CREATE_ACCOUNT_REQUEST = 1;

	private List<DeveloperAccount> developerAccounts;

	private boolean manageAccountsMode = false;
	private boolean blockGoingBack = false;
	private DeveloperAccount selectedAccount = null;
	private View okButton;
	private ListView mAccountsList;

	private AccountManager accountManager;
	private DeveloperAccountManager developerAccountManager;
	private AutosyncHandler syncHandler;

	public void onCreate(Bundle savedInstanceState) {
		super.onCreate(savedInstanceState);

		requestWindowFeature(Window.FEATURE_INDETERMINATE_PROGRESS);

		accountManager = AccountManager.get(this);
		developerAccountManager = DeveloperAccountManager.getInstance(getApplicationContext());
		syncHandler = new AutosyncHandler();

		// When called from accounts action item in Main, this flag is passed to
		// indicate that LoginActivity should not auto login as we are managing the
		// accounts, rather than performing the initial login
		Bundle extras = getIntent().getExtras();
		if (extras != null) {
			manageAccountsMode = extras.getBoolean(LoginActivity.EXTRA_MANAGE_ACCOUNTS_MODE);
		}

		if (manageAccountsMode) {
			getSupportActionBar().setTitle(R.string.manage_accounts);
		}

		selectedAccount = developerAccountManager.getSelectedDeveloperAccount();

		setContentView(R.layout.login);
		setSupportProgressBarIndeterminateVisibility(false);
		mAccountsList = (ListView) findViewById(R.id.login_input);
		mAccountsList.setOnItemClickListener(mItemClickListener);
		mAccountsList.setChoiceMode(ListView.CHOICE_MODE_MULTIPLE);

		okButton = findViewById(R.id.login_ok_button);
		okButton.setClickable(true);
		okButton.setOnClickListener(new OnClickListener() {
			@Override
			public void onClick(View v) {
				new AsyncTask<Void, Void, Void>() {

					@Override
					protected void onPreExecute() {
						setSupportProgressBarIndeterminateVisibility(true);
						okButton.setEnabled(false);
					}

					@Override
					protected Void doInBackground(Void... args) {
						saveDeveloperAccounts();

						return null;
					}

					@Override
					protected void onPostExecute(Void arg) {
						setSupportProgressBarIndeterminateVisibility(false);
						okButton.setEnabled(true);

						if (selectedAccount != null) {
							redirectToMain(selectedAccount.getName(),
									selectedAccount.getDeveloperId());
						} else {
							// Go to the first non hidden account
							for (DeveloperAccount account : developerAccounts) {
								if (account.isVisible()) {
									// If we are using only the first selected item,
									// should we not use single choice listview?
									// if agreeable, put a TODO: here.
									redirectToMain(account.getName(), account.getDeveloperId());
									break;
								}
							}
						}
					}
				}.execute();
			}
		});
	}

	/***
	 * Fires when the listitem is clicked
	 ****/
	private AdapterView.OnItemClickListener mItemClickListener = new AdapterView.OnItemClickListener() {
      @Override
      public void onItemClick(AdapterView<?> adapter, View rowView, int position,
            long id) {
          boolean isChecked = mAccountsList.isItemChecked(position);

    	  Object possiblyViewHolder = rowView.getTag();
    	  ViewHolder holder = null;
    	  if(possiblyViewHolder instanceof ViewHolder) {
    		  holder = (ViewHolder) possiblyViewHolder;
    		  holder.mSelected.setChecked(isChecked);
    	  } else
    		  Log.i(TAG, "not instance of viewholder");

          onAccountSelected(isChecked, position);
      }
   };
   
   private void onAccountSelected(boolean isChecked, int position) {

 	  DeveloperAccount account = developerAccounts.get(position);
		if (isChecked) {
			account.activate();
		} else {
			account.hide();
		}

		if (manageAccountsMode && account.equals(selectedAccount)) {
			// If they remove the current account, then stop them
			// going back
			blockGoingBack = account.isHidden();
		}

		okButton.setEnabled(isAtLeastOneAccountEnabled());
   }
	
	@Override
	protected void onResume() {
		super.onResume();

		boolean skipAutologin = Preferences.getSkipAutologin(this);

		if (!manageAccountsMode & !skipAutologin & selectedAccount != null) {
			redirectToMain(selectedAccount.getName(), selectedAccount.getDeveloperId());
		} else {
			// This method should be called in onCreate.
			// Not sure of the side effects.
			showAccountList();
		}
	}
	
	@Override
	public boolean onCreateOptionsMenu(Menu menu) {
		super.onCreateOptionsMenu(menu);
		getSupportMenuInflater().inflate(R.menu.login_menu, menu);
		return true;
	}

	/**
	 * Called if item in option menu is selected.
	 * 
	 * @param item
	 *            The chosen menu item
	 * @return boolean true/false
	 */
	@Override
	public boolean onOptionsItemSelected(MenuItem item) {
		switch (item.getItemId()) {
			case R.id.itemLoginmenuAdd:
				addNewGoogleAccount();
				break;
			case android.R.id.home:
				if (!blockGoingBack) {
					setResult(RESULT_OK);
					finish();
				}
				break;
			default:
				return false;
		}
		return true;
	}

	@Override
	public void onBackPressed() {
		setResult(blockGoingBack ? RESULT_CANCELED : RESULT_OK);
		super.onBackPressed();
	}

	private void showAccountList() {
		Account[] googleAccounts = accountManager.getAccountsByType(AutosyncHandler.ACCOUNT_TYPE_GOOGLE);
		List<DeveloperAccount> dbAccounts = developerAccountManager.getAllDeveloperAccounts();
		developerAccounts = new ArrayList<DeveloperAccount>();

		for (int i = 0; i < googleAccounts.length; i++) {
			DeveloperAccount developerAccount = DeveloperAccount
					.createHidden(googleAccounts[i].name);
			int idx = dbAccounts.indexOf(developerAccount);
			// use persistent object if exists
			if (idx != -1) {
				developerAccount = dbAccounts.get(idx);
			}
			
			developerAccounts.add(developerAccount);

			// Setup auto sync
			// only do this when managing accounts, otherwise sync may start
			// in the background before accounts are actually configured
			if (manageAccountsMode) {
				// Ensure it matches the sync period (excluding disabled state)
				syncHandler.setAutosyncPeriod(googleAccounts[i].name,
						Preferences.getLastNonZeroAutosyncPeriod(this));
				// Now make it match the master sync (including disabled state)
				syncHandler.setAutosyncPeriod(googleAccounts[i].name,
						Preferences.getAutosyncPeriod(this));
			}
			
		}	// end for
		
		AccountAdaper adapter = new AccountAdaper(this, R.layout.login_list_item, developerAccounts);
		mAccountsList.setAdapter(adapter);

		// Update ok button
		okButton.setEnabled(isAtLeastOneAccountEnabled());
	}

	private void saveDeveloperAccounts() {
		for (DeveloperAccount account : developerAccounts) {
			if (account.isHidden()) {
				// They are removing the account from Andlytics, disable
				// syncing
				syncHandler.setAutosyncEnabled(account.getName(), false);
			} else {
				// Make it match the master sync period (including
				// disabled state)
				syncHandler.setAutosyncPeriod(account.getName(),
						Preferences.getAutosyncPeriod(LoginActivity.this));
			}
			developerAccountManager.addOrUpdateDeveloperAccount(account);
		}
	}

	private boolean isAtLeastOneAccountEnabled() {
		for (DeveloperAccount acc : developerAccounts) {
			if (acc.isVisible()) {
				return true;
			}
		}

		return false;
	}

	private void addNewGoogleAccount() {
		AccountManagerCallback<Bundle> callback = new AccountManagerCallback<Bundle>() {
			public void run(AccountManagerFuture<Bundle> future) {
				try {
					Bundle bundle = future.getResult();
					bundle.keySet();
					Log.d(TAG, "account added: " + bundle);

					showAccountList();

				} catch (OperationCanceledException e) {
					Log.d(TAG, "addAccount was canceled");
				} catch (IOException e) {
					Log.d(TAG, "addAccount failed: " + e);
				} catch (AuthenticatorException e) {
					Log.d(TAG, "addAccount failed: " + e);
				}
				// gotAccount(false);
			}
		};

		// TODO request a weblogin: token here, so we have it cached?
		accountManager.addAccount(AutosyncHandler.ACCOUNT_TYPE_GOOGLE,
				LoginActivity.AUTH_TOKEN_TYPE_ANDROID_DEVELOPER, null, null /* options */,
				LoginActivity.this, callback, null /* handler */);
	}

	private void redirectToMain(String selectedAccount, String developerId) {
		Preferences.saveSkipAutoLogin(this, false);
		Intent intent = new Intent(LoginActivity.this, Main.class);
		intent.putExtra(BaseActivity.EXTRA_AUTH_ACCOUNT_NAME, selectedAccount);
		intent.putExtra(BaseActivity.EXTRA_DEVELOPER_ID, developerId);
		intent.setFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP);
		startActivity(intent);
		overridePendingTransition(R.anim.activity_fade_in, R.anim.activity_fade_out);
		finish();
	}
	
	private CompoundButton.OnCheckedChangeListener mCheckedChangeListener = new OnCheckedChangeListener() {
		@Override
		public void onCheckedChanged(CompoundButton buttonView, boolean isChecked) {
			if(buttonView instanceof CheckBox) {
				CheckBox checkBox = (CheckBox) buttonView;
				
				Object possiblyViewHolder = checkBox.getTag();
	        	ViewHolder holder = null;
	        	if(possiblyViewHolder instanceof ViewHolder) {
	        		holder = (ViewHolder) possiblyViewHolder;
		            onAccountSelected(isChecked, holder.mPosition);
	        	} else
	        		Log.i(TAG, "not an instance of viewholder");
			} else
				Log.w(TAG, "Expect the clicked view to be instance of " + CheckBox.class.getSimpleName() + " Found: " + buttonView);
		}
	};
	
	private static class ViewHolder {
		TextView mAccountName;
		CheckBox mSelected;
		int mPosition;
	}

	private class AccountAdaper extends ArrayAdapter<DeveloperAccount> {
		private Context context;
		private List<DeveloperAccount> accounts;
		private int textViewResourceId;

		public AccountAdaper(Context context, int textViewResourceId,
				List<DeveloperAccount> objects) {
			super(context, textViewResourceId, objects);
			this.context = context;
			this.accounts = objects;
			this.textViewResourceId = textViewResourceId;
		}

		@Override
		public View getView(int position, View convertView, ViewGroup parent) {
			View rowView = convertView;
			ViewHolder holder = null;
			if (rowView == null) {
				LayoutInflater inflater = (LayoutInflater) context
						.getSystemService(Context.LAYOUT_INFLATER_SERVICE);
				holder = new ViewHolder();
				rowView = inflater.inflate(textViewResourceId, parent, false);
				holder.mAccountName = (TextView) rowView.findViewById(R.id.login_list_item_text);
				holder.mSelected = (CheckBox) rowView.findViewById(R.id.login_list_item_enabled);
				rowView.setTag(holder);
			} else {
				holder = (ViewHolder) rowView.getTag();
			}
			
			DeveloperAccount account = accounts.get(position);
			
			// save position so as to use it later
			holder.mPosition = position;
			
			// show account name
			holder.mAccountName.setText(account.getName());
			
			// show select/unselect checkbox
			holder.mSelected.setChecked(!account.isHidden());
			holder.mSelected.setTag(holder);
			holder.mSelected.setOnCheckedChangeListener(mCheckedChangeListener);
			
			return rowView;
		}

	}
	
}