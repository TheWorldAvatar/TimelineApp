package uk.ac.cam.cares.jps.timeline.ui.adapter;

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.material.card.MaterialCardView;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import uk.ac.cam.cares.jps.model.RouteOption;
import uk.ac.cam.cares.jps.timelinemap.R;

// NEW FILE
/**
 * Shows the suggested routes as cards ("Fastest  12 min · 4.3 km"). The selected card is drawn as "checked".
 * Same RecyclerView.Adapter structure as ActivityItemAdapter.
 */
public class RouteAdapter extends RecyclerView.Adapter<RouteAdapter.RouteViewHolder> {

    public interface OnRouteClickListener {
        void onRouteClick(int index);
    }

    private final OnRouteClickListener listener;
    private List<RouteOption> routes = new ArrayList<>();
    private int selectedIndex = 0;

    public RouteAdapter(OnRouteClickListener listener) {
        this.listener = listener;
    }

    public void submit(List<RouteOption> newRoutes, int newSelectedIndex) {
        routes = newRoutes == null ? new ArrayList<>() : new ArrayList<>(newRoutes);
        selectedIndex = newSelectedIndex;
        notifyDataSetChanged();
    }

    public void setSelectedIndex(int newSelectedIndex) {
        selectedIndex = newSelectedIndex;
        notifyDataSetChanged();
    }

    @NonNull
    @Override
    public RouteViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View view = LayoutInflater.from(parent.getContext()).inflate(R.layout.item_route, parent, false);
        return new RouteViewHolder(view);
    }

    @Override
    public void onBindViewHolder(@NonNull RouteViewHolder holder, int position) {
        RouteOption route = routes.get(position);
        holder.label.setText(route.getLabel());
        holder.summary.setText(formatDuration(route.getDurationSeconds()) + " · " + formatDistance(route.getDistanceMeters()));
        holder.card.setChecked(position == selectedIndex);
        holder.card.setOnClickListener(v -> {
            int adapterPosition = holder.getAdapterPosition();
            if (adapterPosition != RecyclerView.NO_POSITION) {
                listener.onRouteClick(adapterPosition);
            }
        });
    }

    @Override
    public int getItemCount() {
        return routes.size();
    }

    static String formatDuration(double seconds) {
        long minutes = Math.round(seconds / 60.0);
        if (minutes < 60) {
            return minutes + " min";
        }
        return (minutes / 60) + " h " + (minutes % 60) + " min";
    }

    static String formatDistance(double meters) {
        if (meters < 1000) {
            return Math.round(meters) + " m";
        }
        return String.format(Locale.US, "%.1f km", meters / 1000.0);
    }

    static class RouteViewHolder extends RecyclerView.ViewHolder {
        final MaterialCardView card;
        final TextView label;
        final TextView summary;

        RouteViewHolder(@NonNull View itemView) {
            super(itemView);
            card = itemView.findViewById(R.id.route_card);
            label = itemView.findViewById(R.id.route_label);
            summary = itemView.findViewById(R.id.route_summary);
        }
    }
}