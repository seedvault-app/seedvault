package com.stevesoltys.backup.activity;

import android.app.Activity;
import android.view.MenuItem;
import android.view.View;
import android.widget.AdapterView;
import android.widget.ListView;

import com.stevesoltys.backup.R;

import java.util.HashSet;
import java.util.Set;
import java.util.stream.IntStream;

/**
 * @author Steve Soltys
 */
public abstract class PackageListActivity extends Activity implements AdapterView.OnItemClickListener {

    protected ListView packageListView;

    protected final Set<String> selectedPackageList = new HashSet<>();

    public void onItemClick(AdapterView<?> parent, View view, int position, long id) {
        String clickedPackage = (String) packageListView.getItemAtPosition(position);

        if (!selectedPackageList.remove(clickedPackage)) {
            selectedPackageList.add(clickedPackage);
            packageListView.setItemChecked(position, true);

        } else {
            packageListView.setItemChecked(position, false);
        }
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {

        if (item.getItemId() == R.id.action_unselect_all) {

            IntStream.range(0, packageListView.getCount())
                    .forEach(position -> {
                        selectedPackageList.remove((String) packageListView.getItemAtPosition(position));
                        packageListView.setItemChecked(position, false);
                    });

            return true;
        }

        return super.onOptionsItemSelected(item);
    }

    public void preSelectAllPackages() {
        IntStream.range(0, packageListView.getCount())
                .forEach(position -> {
                    selectedPackageList.add((String) packageListView.getItemAtPosition(position));
                    packageListView.setItemChecked(position, true);
                });
    }
}
