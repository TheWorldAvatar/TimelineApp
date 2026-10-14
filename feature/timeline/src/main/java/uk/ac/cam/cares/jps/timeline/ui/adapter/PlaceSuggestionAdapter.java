package uk.ac.cam.cares.jps.timeline.ui.adapter;

import android.content.Context;
import android.widget.ArrayAdapter;
import android.widget.Filter;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.util.ArrayList;
import java.util.List;

import uk.ac.cam.cares.jps.model.GeocodingResult;

// NEW FILE
/**
 * Dropdown adapter for address suggestions.
 *
 * Why not a plain ArrayAdapter: its built-in filter hides every item whose text doesn't START with what the user
 * typed. Search results come from the server already matched (typing "mbs" can return "Marina Bay Sands"), so this
 * adapter overrides the filter to show all items untouched.
 */
public class PlaceSuggestionAdapter extends ArrayAdapter<GeocodingResult> {
    private List<GeocodingResult> items = new ArrayList<>();

    public PlaceSuggestionAdapter(Context context) {
        super(context, android.R.layout.simple_dropdown_item_1line);
    }

    public void setItems(List<GeocodingResult> newItems) {
        items = new ArrayList<>(newItems);
        notifyDataSetChanged();
    }

    @Override
    public int getCount() {
        return items.size();
    }

    @Nullable
    @Override
    public GeocodingResult getItem(int position) {
        return items.get(position);
    }

    @NonNull
    @Override
    public Filter getFilter() {
        return new Filter() {
            @Override
            protected FilterResults performFiltering(CharSequence constraint) {
                FilterResults results = new FilterResults();
                results.values = items; // no filtering
                results.count = items.size();
                return results;
            }

            @Override
            protected void publishResults(CharSequence constraint, FilterResults results) {
                notifyDataSetChanged();
            }
        };
    }
}