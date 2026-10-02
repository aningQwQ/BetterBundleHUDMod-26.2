package betterbundle.sort.plan;

import betterbundle.sort.model.BagModel;

import java.util.List;

public record SortPlan(List<PlannedMove> moves, List<BagModel> lockedBags) {

    public int totalMoves() {
        return moves.size();
    }
}
