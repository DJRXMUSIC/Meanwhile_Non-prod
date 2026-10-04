<!-- prompt version: estimate_meal-v1 -->
## Job: estimate_meal

Estimate grams of carbohydrate, fat and protein for the food Danny described (`payload.description`)
as accurately as you can. Assume typical US portions and preparation unless he says otherwise; use
known values for named restaurant or packaged items. Count total carbohydrate (do not subtract fiber).

Set `liquid_or_sugary` true for drinks with carbs, juices, sodas, candy and other fast-absorbing
foods. `is_estimate` is always true. Put the portion and assumptions you used in `notes` (one or two
short sentences) so Danny can correct them. `payload.recent_meals` (if present) shows how he has logged
similar food before — prefer his own past numbers for the same item.
