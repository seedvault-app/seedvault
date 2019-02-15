package com.stevesoltys.backup.activity.restore;

import android.app.Activity;
import android.net.Uri;
import android.os.Bundle;
import android.view.Menu;
import android.view.MenuInflater;
import android.view.MenuItem;
import android.view.View;
import android.widget.AdapterView;
import android.widget.ListView;
import com.stevesoltys.backup.R;

import java.util.HashSet;
import java.util.Set;
import java.util.stream.IntStream;

public class RestoreBackupActivity extends Activity implements View.OnClickListener, AdapterView.OnItemClickListener {

    private RestoreBackupActivityController controller;

    private ListView packageListView;

    private Set<String> selectedPackageList;

    private Uri contentUri;

    @Override
    public void onClick(View view) {
        int viewId = view.getId();

        switch (viewId) {

            case R.id.restore_confirm_button:
                controller.showEnterPasswordAlert(selectedPackageList, contentUri, this);
                break;
        }
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        setContentView(R.layout.activity_restore_backup);
        findViewById(R.id.restore_confirm_button).setOnClickListener(this);

        packageListView = findViewById(R.id.restore_package_list);
        selectedPackageList = new HashSet<>();
        contentUri = getIntent().getData();

        controller = new RestoreBackupActivityController();
        controller.populatePackageList(packageListView, contentUri, this);
    }

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        MenuInflater inflater = getMenuInflater();
        inflater.inflate(R.menu.backup_menu, menu);
        return true;
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {

        switch (item.getItemId()) {

            case R.id.action_select_all:

                IntStream.range(0, packageListView.getCount())
                        .forEach(position -> {
                            selectedPackageList.add((String) packageListView.getItemAtPosition(position));
                            packageListView.setItemChecked(position, true);
                        });

                return true;

            default:
                return super.onOptionsItemSelected(item);

        }
    }

    @Override
    public void onItemClick(AdapterView<?> parent, View view, int position, long id) {
        String clickedPackage = (String) packageListView.getItemAtPosition(position);

        if (!selectedPackageList.remove(clickedPackage)) {
            selectedPackageList.add(clickedPackage);
            packageListView.setItemChecked(position, true);

        } else {
            packageListView.setItemChecked(position, false);
        }
    }
}
