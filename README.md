# Economy

A PaperMC plugin implementing a basic currency (Coin) and item trading mechanic.

## Commands

```
/balance
```

Shows your amount of Coin.

```
/sell <item> <quantity> <price>
```

Create a bill of sale for some items at a specified price.

```
/buy <item> <quantity> <price>
```

Create a bill of purchase for some items at a specified price.

```
/bank <buy|sell> <item> <quantity> <price>
```

Create a special Bank bill of sale/purchase. These offers have infinite supply/demand.

This is a privileged command.

## Bills

Bills of sale are special pieces of paper that may be applied to a wooden sign by right clicking.

Interacting with a sign using a bill of sale will place a "Sale Request" sign. Other players may right click the sign to buy the item.

Interacting with a sign using a bill of purchase will place a "Purchase Request" sign. Other players may right click the sign to fulfill the purchase(selling their items). Once their sign is marked "Fulfilled", the buyer may right click to claim their items.
