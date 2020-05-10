package xyz.guiand.mc.Economy;

import com.destroystokyo.paper.event.block.BlockDestroyEvent;
import org.bukkit.ChatColor;
import org.bukkit.Material;
import org.bukkit.OfflinePlayer;
import org.bukkit.block.BlockState;
import org.bukkit.block.Sign;
import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.java.JavaPlugin;
import org.sqlite.SQLiteConfig;

import javax.annotation.Nonnull;
import java.sql.*;
import java.util.*;
import java.util.function.Function;
import java.util.logging.Level;
import java.util.logging.Logger;

class Trade {
    enum Type {
        SaleReq,
        PurchaseReq,
        Fulfillment
    }

    public static final Trade SaleReq = new Trade(Type.SaleReq);
    public static final Trade PurchaseReq = new Trade(Type.PurchaseReq);
    public static final Trade Fulfillment = new Trade(Type.Fulfillment);

    Type type;

    public Trade(Type type) {
        this.type = type;
    }

    public String toString() {
        switch (type) {
            case PurchaseReq:
                return "PurchaseReq";
            case SaleReq:
                return "SaleReq";
            case Fulfillment:
                return "Fulfillment";
        }
        assert false;
        return null;
    }

    public static Trade fromString(String str) {
        switch (str) {
            case "PurchaseReq":
                return new Trade(Type.PurchaseReq);
            case "SaleReq":
                return new Trade(Type.SaleReq);
            case "Fulfillment":
                return new Trade(Type.Fulfillment);
        }
        assert false;
        return null;
    }
}

final class EconomyDb {
    private final Logger l;
    private Connection dbConn;

    public EconomyDb(String path, Logger l) {
        this.l = l;
        SQLiteConfig config = new SQLiteConfig();
        config.setTransactionMode(SQLiteConfig.TransactionMode.EXCLUSIVE);

        try {
            dbConn = DriverManager.getConnection("jdbc:sqlite:" + path,
                    config.toProperties());

            Statement dbStmt = dbConn.createStatement();
            dbStmt.execute(
                    "CREATE TABLE IF NOT EXISTS players (" +
                            "[id] STRING PRIMARY KEY NOT NULL, " +
                            "[balance] INTEGER NOT NULL" +
                            ")"
            );
            dbStmt.execute(
                    "CREATE TABLE IF NOT EXISTS trades (" +
                            "[id] STRING PRIMARY KEY NOT NULL, " +
                            "[type] STRING NOT NULL, " +
                            "[owner] STRING, " +
                            "[items] STRING NOT NULL, " +
                            "[price] INTEGER NOT NULL, " +
                            "FOREIGN KEY (owner) REFERENCES players(id)" +
                            ")"
            );
            dbStmt.execute(
                    "CREATE TABLE IF NOT EXISTS tradeSigns (" +
                            "[location] STRING PRIMARY KEY NOT NULL, " +
                            "[tradeId] STRING NOT NULL, " +
                            "FOREIGN KEY (tradeId) REFERENCES trades(id) " +
                            ")"
            );
        } catch (SQLException e) {
            l.log(Level.SEVERE, "Error initializing SQLite! " + e);
        }
    }

    void close() {
        try {
            dbConn.close();
        } catch (SQLException e) {
        }
    }

    public ResultSet getDataForPlayer(OfflinePlayer p) {
        if (p == null)
            return null;
        String uuid = p.getUniqueId().toString();

        try {
            PreparedStatement stmt = dbConn.prepareStatement("SELECT * FROM players WHERE id=?");
            stmt.setString(1, uuid);

            ResultSet results = stmt.executeQuery();
            if (!results.next()) {
                PreparedStatement insertStmt = dbConn.prepareStatement("INSERT INTO players(id, balance) values(?,?)");
                insertStmt.setString(1, uuid);
                insertStmt.setInt(2, 0);
                insertStmt.executeUpdate();
                results = stmt.executeQuery();
                results.next();
            }
            return results;
        } catch (SQLException e) {
            l.log(Level.SEVERE, "Error getting player data! " + e);
            return null;
        }
    }

    public int getPlayerBalance(OfflinePlayer p) {
        try {
            return getDataForPlayer(p).getInt("balance");
        } catch (SQLException e) {
            l.log(Level.SEVERE, "Error getting player balance! " + e);
            return 0;
        }
    }

    public void setPlayerBalance(OfflinePlayer p, int balance) {
        String uuid = p.getUniqueId().toString();
        try {
            PreparedStatement stmt = dbConn.prepareStatement("UPDATE players SET balance=? WHERE id=?");
            stmt.setInt(1, balance);
            stmt.setString(2, uuid);
            stmt.executeUpdate();
        } catch (SQLException e) {
            l.log(Level.SEVERE, "Error setting player balance! " + e);
        }
    }

    public ResultSet getTradeData(String tradeId) {
        try {
            PreparedStatement stmt = dbConn.prepareStatement("SELECT * FROM trades WHERE id=?");
            stmt.setString(1, tradeId);
            ResultSet result = stmt.executeQuery();
            if (result.next())
                return result;
            return null;
        } catch (SQLException e) {
            l.log(Level.SEVERE, "Error getting trade data! " + e);
            return null;
        }
    }

    public ResultSet getTradeData(Sign sign) {
        try {
            PreparedStatement stmt = dbConn.prepareStatement(
                    "SELECT * FROM tradeSigns " +
                            "INNER JOIN trades " +
                            "ON tradeSigns.tradeId = trades.id " +
                            "WHERE tradeSigns.location=?");
            stmt.setString(1, sign.getLocation().toString());
            ResultSet result = stmt.executeQuery();
            if (result.next())
                return result;
            return null;
        } catch (SQLException e) {
            l.log(Level.SEVERE, "Error getting trade data! " + e);
            return null;
        }
    }

    public void setTradeFulfilled(Sign sign) {
        try {
            PreparedStatement stmt = dbConn.prepareStatement(
                    "UPDATE trades SET type=? WHERE id=" +
                            "(SELECT tradeId FROM tradeSigns WHERE location=?)");
            stmt.setString(1, Trade.Fulfillment.toString());
            stmt.setString(2, sign.getLocation().toString());
            stmt.executeUpdate();
        } catch (SQLException e) {
            l.log(Level.SEVERE, "Error setting trade fulfilled! " + e);
        }
    }

    public UUID writeTradeData(OfflinePlayer owner, List<ItemStack> items, int price, Trade type) {
        // Ensures player present in db for trades foreign key
        getDataForPlayer(owner);

        UUID saleId = UUID.randomUUID();
        YamlConfiguration itemsYaml = new YamlConfiguration();
        itemsYaml.set("stack", items);

        String ownerId = null;
        if (owner != null)
            ownerId = owner.getUniqueId().toString();

        try {
            PreparedStatement stmt = dbConn.prepareStatement(
                    "INSERT INTO trades(id, owner, items, price, type) values(?,?,?,?,?)");
            stmt.setString(1, saleId.toString());
            stmt.setString(2, ownerId);
            stmt.setString(3, itemsYaml.saveToString());
            stmt.setInt(4, price);
            stmt.setString(5, type.toString());
            stmt.executeUpdate();
        } catch (SQLException e) {
            l.log(Level.SEVERE, "Error writing trade data! " + e);
            return null;
        }
        return saleId;
    }

    public void writeSignData(Sign sign, String tradeId) {
        try {
            PreparedStatement stmt = dbConn.prepareStatement(
                    "INSERT INTO tradeSigns(location, tradeId) values(?, ?)");
            stmt.setString(1, sign.getLocation().toString());
            stmt.setString(2, tradeId);
            stmt.executeUpdate();
        } catch (SQLException e) {
            l.log(Level.SEVERE, "Error writing sign data! " + e);
        }
    }

    public ResultSet removeTradeData(Sign sign) {
        try {
            ResultSet results = getTradeData(sign);
            PreparedStatement stmt = dbConn.prepareStatement(
                    "DELETE FROM tradeSigns WHERE location=?");
            stmt.setString(1, sign.getLocation().toString());
            stmt.executeUpdate();
            stmt = dbConn.prepareStatement(
                    "DELETE FROM trades WHERE id=?");
            stmt.setString(1, results.getString("tradeId"));
            stmt.executeUpdate();
            return results;
        } catch (SQLException e) {
            l.log(Level.SEVERE, "Error removing sign data! " + e);
            return null;
        }
    }
}


public final class Economy extends JavaPlugin implements Listener {
    EconomyDb db;
    TreeMap<String, Material> matMap;

    @Override
    public void onEnable() {
        getServer().getPluginManager().registerEvents(this, this);
        getDataFolder().mkdir();
        String dbPath = getDataFolder().getAbsolutePath() + "/economy.sqlite";
        db = new EconomyDb(dbPath, getLogger());

        matMap = new TreeMap<>();
        for (Material material : Material.values()) {
            matMap.put(material.toString().toLowerCase(), material);
        }
    }

    @Override
    public void onDisable() {
        db.close();
    }

    private static boolean runAsPlayer(CommandSender sender, Function<Player, Boolean> f) {
        if (sender instanceof Player) {
            return f.apply((Player) sender);
        } else {
            sender.sendMessage("You must be a player to use this command!");
            return false;
        }
    }

    @Override
    public boolean onCommand(@Nonnull CommandSender sender, Command cmd, @Nonnull String label, @Nonnull String[] args) {
        switch (cmd.getName()) {
            case "bank":
                return runAsPlayer(sender, (Player p) -> onBankCommand(p, args));
            case "sell":
                return runAsPlayer(sender, (Player p) -> onSellCommand(p, args));
            case "buy":
                return runAsPlayer(sender, (Player p) -> onBuyCommand(p, args));
            case "balance":
                return runAsPlayer(sender, (Player p) -> onBalanceCommand(p, args));
        }
        return false;
    }

    private List<String> completeMaterial(String partial) {
        List<String> results = new ArrayList<>();
        for (String greaterEq : matMap.tailMap(partial).keySet()) {
            if (!greaterEq.startsWith(partial))
                break;
            results.add(greaterEq);
        }
        return results;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command cmd, String label, String[] args) {
        switch (cmd.getName()) {
            case "bank":
                if (args.length == 1) {
                    if (args[0].startsWith("b"))
                        return Collections.singletonList("buy");
                    if (args[0].startsWith("s"))
                        return Collections.singletonList("sell");
                    return new ArrayList<>();
                }
                args = Arrays.copyOfRange(args, 1, args.length);
            case "sell":
            case "buy":
                if (args.length == 1)
                    return completeMaterial(args[0]);
                break;
        }
        return null;
    }

    private boolean onBalanceCommand(Player p, String[] args) {
        if (args.length != 0)
            return false;
        int balance = db.getPlayerBalance(p);
        p.sendMessage("You have " + balance + " Coin.");
        return true;
    }

    private boolean onBankCommand(Player sender, String[] args) {
        if (args.length == 0)
            return false;
        if (args[0].equalsIgnoreCase("sell"))
            return onSellCommand(sender, null, Arrays.copyOfRange(args, 1, args.length));
        if (args[0].equalsIgnoreCase("buy"))
            return onBuyCommand(sender, null, Arrays.copyOfRange(args, 1, args.length));
        return false;
    }

    private List<ItemStack> takeItemsFromInventory(Player p, Material material, int amount)
            throws RuntimeException {
        Inventory inv = p.getInventory();

        if (!inv.contains(material, amount)) {
            throw new RuntimeException("You do not have enough " + material.name() + " in your inventory!");
        }
        List<ItemStack> saleItems = new ArrayList<>();

        int amountLeft = amount;
        while (amountLeft != 0) {
            int itemLocation = inv.first(material);
            ItemStack stack = inv.getItem(itemLocation);
            assert stack != null;
            int stackSize = stack.getAmount();
            if (amountLeft < stackSize) {
                stack.subtract(amountLeft);
                ItemStack saleStack = stack.clone();
                saleStack.setAmount(amountLeft);
                saleItems.add(saleStack);
                amountLeft = 0;
            } else {
                inv.remove(stack);
                saleItems.add(stack);
                amountLeft -= stackSize;
            }
        }
        return saleItems;
    }

    private void takeItemsFromInventory(Player p, List<ItemStack> items)
            throws RuntimeException{
        Inventory inv = p.getInventory();
        ItemStack[] backup = inv.getStorageContents().clone();

        for (ItemStack stack : items) {
            if (!inv.removeItemAnySlot(stack).isEmpty()) {
                // Failed to remove item
                inv.setStorageContents(backup);
                throw new RuntimeException("You do not have enough of these items in your inventory!");
            }
        }
    }

    private List<ItemStack> materializeItems(Material material, int amount) {
        List<ItemStack> items = new ArrayList<>();
        while (amount != 0) {
            int stackSize = Math.min(amount, material.getMaxStackSize());
            items.add(new ItemStack(material, stackSize));
            amount -= stackSize;
        }
        return items;
    }

    private boolean onTradeCommand(@Nonnull Player sender, Player target, Trade type, String[] args) {
        if (args.length != 3)
            return false;

        String itemName = args[0];
        String amountStr = args[1];
        String priceStr = args[2];
        int amount, price;
        try {
            amount = Integer.parseInt(amountStr);
            price = Integer.parseInt(priceStr);
        } catch (NumberFormatException e) {
            return false;
        }
        if (amount <= 0)
            return false;

        Material material = Material.matchMaterial(itemName);
        if (material == null) {
            sender.sendMessage("Could not understand item type `" + itemName + "`!");
            return false;
        }

        List<ItemStack> items = materializeItems(material, amount);
        itemName = items.get(0).getI18NDisplayName();
        UUID saleId = db.writeTradeData(target, items, price, type);

        String offerStr, billName;
        switch (type.type) {
            case SaleReq:
                offerStr = "SELL OFFER";
                billName = "Bill of Sale";

                if (target != null) {
                    if (target.getInventory().firstEmpty() == -1) {
                        sender.sendMessage("You cannot fit a sale sign in your inventory!");
                    }
                    try {
                        takeItemsFromInventory(target, material, amount);
                    } catch (RuntimeException e) {
                        sender.sendMessage(e.getMessage());
                        return false;
                    }
                }
                break;

            case PurchaseReq:
                offerStr = "BUY OFFER";
                billName = "Bill of Purchase";

                if (target != null) {
                    db.setPlayerBalance(target, db.getPlayerBalance(target) - price);
                }
                break;

            default:
                assert false;
                return false;
        }

        ItemStack signItem = new ItemStack(Material.PAPER);
        List<String> lore = new ArrayList<>();
        lore.add(String.format("%s: %d %s", offerStr, amount, itemName));
        lore.add(price + " Coin");
        lore.add("Use by placing this bill on a wooden sign!");
        lore.add(saleId.toString());
        signItem.setLore(lore);
        signItem.getItemMeta().setDisplayName(ChatColor.DARK_PURPLE + billName);

        sender.getInventory().addItem(signItem);
        return true;
    }

    private boolean onSellCommand(Player p, String[] args) {
        return onSellCommand(p, p, args);
    }

    private boolean onSellCommand(@Nonnull Player sender, Player target, String[] args) {
        return onTradeCommand(sender, target, Trade.SaleReq, args);
    }

    private boolean onBuyCommand(Player p, String[] args) {
        return onBuyCommand(p, p, args);
    }

    private boolean onBuyCommand(@Nonnull Player sender, Player target, String[] args) {
        return onTradeCommand(sender, target, Trade.PurchaseReq, args);
    }

    private void onPlaceTrade(Player p, Sign sign, ItemStack bill) {
        List<String> lore = bill.getLore();

        if (lore == null || lore.size() != 4)
            return;
        String tradeId = lore.get(3);
        String ownerId;
        String ownerName;

        try {
            ownerId = db.getTradeData(tradeId).getString("owner");
        } catch (SQLException e) {
            return;
        }

        if (ownerId != null) {
            UUID playerUuid = UUID.fromString(ownerId);
            ownerName = getServer().getOfflinePlayer(playerUuid).getName();
        } else {
            ownerName = ChatColor.DARK_PURPLE + "THE BANK" + ChatColor.RESET;
        }

        if (lore.get(0).startsWith("SELL OFFER: ")) {
            String whatText = lore.get(0).substring("SELL OFFER: ".length());
            sign.setLine(0, ChatColor.DARK_BLUE + "[SELL OFFER]");
            sign.setLine(1, whatText);
            sign.setLine(2, ChatColor.LIGHT_PURPLE + lore.get(1));
            sign.setLine(3, "~~" + ownerName);
            sign.update();

            getServer().broadcastMessage(
                    String.format("%s offers to sell %s%s%s for %s at (%d, %d, %d)!",
                            ownerName,
                            ChatColor.AQUA, whatText,
                            ChatColor.RESET, lore.get(1), sign.getX(), sign.getY(), sign.getZ()));
        } else if (lore.get(0).startsWith("BUY OFFER: ")) {
            String whatText = lore.get(0).substring("BUY OFFER: ".length());
            sign.setLine(0, ChatColor.DARK_BLUE + "[BUY OFFER]");
            sign.setLine(1, whatText);
            sign.setLine(2, ChatColor.LIGHT_PURPLE + lore.get(1));
            sign.setLine(3, "~~" + ownerName);
            sign.update();

            getServer().broadcastMessage(
                    String.format("%s offers to buy %s%s%s for %s at (%d, %d, %d)!",
                            ownerName,
                            ChatColor.AQUA, whatText,
                            ChatColor.RESET, lore.get(1), sign.getX(), sign.getY(), sign.getZ()));
        }

        if (ownerId != null) {
            p.getInventory().remove(bill);
        }
        db.writeSignData(sign, tradeId);
    }

    @SuppressWarnings("unchecked")
    private List<ItemStack> deserializeItemList(String itemsStr) {
        YamlConfiguration itemsYaml = new YamlConfiguration();
        try {
            itemsYaml.loadFromString(itemsStr);
        } catch (InvalidConfigurationException e) {
            getLogger().log(Level.SEVERE, "Found malformed item YAML! " + e);
            return null;
        }
        return (List<ItemStack>) itemsYaml.getList("stack");
    }

    private boolean inventoryCanFitItems(Inventory inv, List<ItemStack> items) {
        int emptySlots = 0;
        for (int i = 0; i < inv.getSize(); i++) {
            if (inv.getItem(i) == null)
                emptySlots++;
            if (emptySlots == items.size())
                return true;
        }
        return false;
    }

    private void onAcceptTrade(Player p, Sign sign, ResultSet trade) {
        int price;
        String owner;
        String itemsStr;
        Trade tradeTy;
        try {
            price = trade.getInt("price");
            owner = trade.getString("owner");
            itemsStr = trade.getString("items");
            tradeTy = Trade.fromString(trade.getString("type"));
        } catch (SQLException e) {
            getLogger().log(Level.SEVERE, "Found malformed item schema! " + e);
            return;
        }

        List<ItemStack> items = deserializeItemList(itemsStr);
        if (items == null)
            return;
        int clientBalance = db.getPlayerBalance(p);
        boolean isBankTrade = owner == null;
        int ownerGain, clientGain;
        String successText;
        Inventory buyerInv;

        switch (tradeTy.type) {
            case SaleReq:
                ownerGain = price;
                clientGain = -price;
                successText = "!! SOLD !!";

                if (price > clientBalance) {
                    p.sendMessage("You don't have enough money to buy this!");
                    return;
                }
                buyerInv = p.getInventory();
                if (!inventoryCanFitItems(buyerInv, items)) {
                    p.sendMessage("You don't have enough space in your inventory for this!");
                    return;
                }

                // Success
                for (ItemStack item : items) {
                    buyerInv.addItem(item);
                }
                if (!isBankTrade) {
                    db.removeTradeData(sign);
                }
                break;

            case PurchaseReq:
                ownerGain = 0;
                clientGain = price;
                successText = "!! FULFILLED !!";

                try {
                    takeItemsFromInventory(p, items);
                } catch (RuntimeException e) {
                    p.sendMessage(e.getMessage());
                    return;
                }

                // Success
                if (!isBankTrade) {
                    db.setTradeFulfilled(sign);
                }
                break;

            case Fulfillment:
                if (!p.getUniqueId().toString().equals(owner)) {
                    p.sendMessage("You must own the sign to claim these fulfilled items!");
                    return;
                }

                ownerGain = 0;
                clientGain = 0;
                successText = "!! CLAIMED !!";

                buyerInv = p.getInventory();
                if (!inventoryCanFitItems(buyerInv, items)) {
                    p.sendMessage("You don't have enough space in your inventory for this!");
                    return;
                }

                // Success
                for (ItemStack item : items) {
                    buyerInv.addItem(item);
                }
                assert (!isBankTrade);
                db.removeTradeData(sign);
                break;

            default: assert false; return;
        }

        db.setPlayerBalance(p, clientBalance + clientGain);
        if (owner != null) {
            // Bank sales remain forever, and there's no account to keep balance.
            UUID ownerUuid = UUID.fromString(owner);
            OfflinePlayer ownerPlayer = getServer().getOfflinePlayer(ownerUuid);
            db.setPlayerBalance(ownerPlayer, db.getPlayerBalance(ownerPlayer) + ownerGain);

            sign.setLine(0, ChatColor.RED + successText);
            sign.update();
        }
    }

    @EventHandler
    public void onUseSign(PlayerInteractEvent ev) {
        Player p = ev.getPlayer();
        if (ev.getAction() != Action.RIGHT_CLICK_BLOCK)
            return;
        BlockState block = ev.getClickedBlock().getState();
        if (!(block instanceof Sign))
            return;
        Sign sign = (Sign) block;

        ResultSet trade = db.getTradeData(sign);
        if (trade != null) {
            onAcceptTrade(p, sign, trade);
        } else {
            ItemStack placedItem = ev.getItem();
            if (placedItem == null || placedItem.getType() != Material.PAPER)
                return;
            onPlaceTrade(p, sign, placedItem);
        }
    }

    @EventHandler
    public void onDestroySign(BlockDestroyEvent ev) {
        BlockState block = ev.getBlock().getState();
        if (!(block instanceof Sign))
            return;

        String tradeId = null;
        try {
            tradeId = db.getTradeData((Sign) block).getString("id");
        } catch (SQLException e) {
        }


        if (tradeId == null)
            return;
        ev.setCancelled(true);
    }

    @EventHandler
    public void onBreakSign(BlockBreakEvent ev) {
        BlockState block = ev.getBlock().getState();
        if (!(block instanceof Sign))
            return;

        String tradeId = null;
        try {
            tradeId = db.getTradeData((Sign) block).getString("id");
        } catch (SQLException e) {
        }

        if (tradeId == null)
            return;
        ev.setCancelled(true);
    }
}
