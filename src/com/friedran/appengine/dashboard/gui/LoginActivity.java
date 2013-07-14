package com.friedran.appengine.dashboard.gui;

import android.accounts.Account;
import android.accounts.AccountManager;
import android.app.Activity;
import android.app.ProgressDialog;
import android.content.Intent;
import android.os.Bundle;
import android.util.Log;
import android.view.View;
import android.widget.*;
import com.friedran.appengine.dashboard.R;
import com.friedran.appengine.dashboard.client.AppEngineDashboardAPI;
import com.friedran.appengine.dashboard.client.AppEngineDashboardAuthenticator;
import com.friedran.appengine.dashboard.client.AppEngineDashboardClient;
import com.friedran.appengine.dashboard.utils.DashboardPreferences;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

public class LoginActivity extends Activity implements View.OnClickListener,
        AppEngineDashboardAuthenticator.OnUserInputRequiredCallback,
        AppEngineDashboardClient.PostExecuteCallback {

    public static final String EXTRA_ACCOUNT = "EXTRA_ACCOUNT";

    protected LinearLayout mEnterAccountLayout;
    protected Spinner mAccountSpinner;
    protected Button mLoginButton;
    protected ProgressDialog mProgressDialog;
    protected AppEngineDashboardClient mAppEngineClient;
    protected DashboardPreferences mPreferences;

    protected List<Account> mAccounts;
    protected Account mSavedAccount;

    // State parameters
    protected boolean mLoginInProgress;
    protected boolean mHasRequestedUserInput;

    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.login);

        mEnterAccountLayout = (LinearLayout) findViewById(R.id.login_enter_account_layout);

        mAccounts = Arrays.asList(AccountManager.get(this).getAccountsByType("com.google"));
        List<String> accountNames = new ArrayList<String>();
        for (Account account : mAccounts) {
            accountNames.add(account.name);
        }

        ArrayAdapter<String> adapter = new ArrayAdapter<String>(
                this, android.R.layout.simple_spinner_item, new ArrayList<String>(accountNames));
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        mAccountSpinner = (Spinner) findViewById(R.id.login_account_spinner);
        mAccountSpinner.setAdapter(adapter);

        mLoginButton = (Button) findViewById(R.id.login_button);
        mLoginButton.setOnClickListener(this);

        mProgressDialog = new ProgressDialog(this);
        mProgressDialog.setTitle("Loading");

        mPreferences = new DashboardPreferences(this);

        mSavedAccount = mPreferences.getSavedAccount();

        mLoginInProgress = false;
        mHasRequestedUserInput = false;
    }

    @Override
    protected void onResume() {
        super.onResume();

        // If we're in the middle of the login process, then continue automatically
        if (mLoginInProgress) {
            mEnterAccountLayout.setVisibility(View.INVISIBLE);
            mAppEngineClient.executeAuthentication();

        // if we have a saved account then automatically start the login process with it
        } else if (mSavedAccount != null) {
            mEnterAccountLayout.setVisibility(View.INVISIBLE);
            startAuthentication(mSavedAccount);

        // Nothing? Wait for the "Login" click
        } else {
            mEnterAccountLayout.setVisibility(View.VISIBLE);
        }
    }

    /** Happens when the login button is clicked */
    @Override
    public void onClick(View v) {
        Account selectedAccount = mAccounts.get(mAccountSpinner.getSelectedItemPosition());

        startAuthentication(selectedAccount);
    }

    private void startAuthentication(Account selectedAccount) {
        mAppEngineClient = new AppEngineDashboardClient(selectedAccount, this, this, this);

        AppEngineDashboardAPI appEngineAPI = AppEngineDashboardAPI.getInstance();
        appEngineAPI.setClient(selectedAccount, mAppEngineClient);

        showProgressDialog("Authenticating with Google AppEngine...");
        mLoginInProgress = true;
        mAppEngineClient.executeAuthentication();
    }

    // Called when the user approval is required to authorize us
    @Override
    public void onUserInputRequired(Intent accountManagerIntent) {
        // If we've already requested the user input and failed, then stop the login process and
        // wait for him to click the "Login" button again.
        if (mHasRequestedUserInput) {
            dismissProgress(true);
            resetSavedAccount();
            return;
        }

        mHasRequestedUserInput = true;
        startActivity(accountManagerIntent);
    }

    // Called when the authentication is completed
    @Override
    public void onPostExecute(Bundle resultBundle) {
        boolean result = resultBundle.getBoolean(AppEngineDashboardClient.KEY_RESULT);
        Log.i("LoginActivity", "Authentication done, result = " + result);

        if (result) {
            onSuccessfulAuthentication();
        } else {
            onFailedLogin("Authentication failed, please make sure you have Internet connectivity and try again");
        }
    }

    private void onSuccessfulAuthentication() {
        showProgressDialog("Retrieving AppEngine applications...");

        mAppEngineClient.executeGetApplications(new AppEngineDashboardClient.PostExecuteCallback() {
            @Override
            public void onPostExecute(Bundle resultBundle) {
                boolean result = resultBundle.getBoolean(AppEngineDashboardClient.KEY_RESULT);
                Log.i("LoginActivity", "GetApplications done, result = " + result);
                Account targetAccount = mAppEngineClient.getAccount();

                if (!result) {
                    onFailedLogin("Failed retrieving list of applications for " + targetAccount.name);
                    return;
                }

                if (resultBundle.getStringArrayList(AppEngineDashboardClient.KEY_APPLICATIONS).size() == 0) {
                    onFailedLogin("No applications found for " + targetAccount.name);
                    return;
                }

                onSuccessfulLogin(targetAccount);
                dismissProgress(true);
            }
        });
    }

    private void onFailedLogin(String message) {
        dismissProgress(true);
        resetSavedAccount();
        Toast.makeText(LoginActivity.this, message, 5000).show();
    }

    private void onSuccessfulLogin(Account account) {
        // Updates the saved account if required
        if (!account.equals(mSavedAccount)) {
            mPreferences.saveAccount(account);
            Log.i("LoginActivity", "Saved account " + account);
        }

        Intent intent = new Intent(this, DashboardActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TASK | Intent.FLAG_ACTIVITY_NEW_TASK)
                .putExtra(LoginActivity.EXTRA_ACCOUNT, account);
        startActivity(intent);
    }

    @Override
    protected void onPause() {
        super.onPause();
        dismissProgress(false);
    }

    @Override
    protected void onStop() {
        super.onStop();
        dismissProgress(false);
    }

    private void showProgressDialog(String message) {
        mProgressDialog.setMessage(message);
        mProgressDialog.show();
        mLoginButton.setClickable(false);
    }

    private void dismissProgress(boolean stopLoginProcess) {
        if (mProgressDialog.isShowing())
            mProgressDialog.dismiss();

        mLoginButton.setClickable(true);
        if (stopLoginProcess) {
            Log.i("LoginActivity", "Login progress stopped");
            mLoginInProgress = false;
            mHasRequestedUserInput = false;
        }
    }

    private void resetSavedAccount() {
        mPreferences.resetSavedAccount();

        mSavedAccount = null;
        mEnterAccountLayout.setVisibility(View.VISIBLE);
    }
}