import pandas as pd
import numpy as np
import seaborn as sns
import matplotlib.pyplot as plt
from scipy.stats import kruskal, mannwhitneyu
from itertools import combinations

# --- Example data ---
groupA = [12.1, 12.3, 12.2, 12.5]
groupB = [10.5, 10.8, 10.6, 10.7]
groupC = [14.0, 14.2, 14.1, 14.3]

groups = {'A': groupA, 'B': groupB, 'C': groupC}

# --- Combine into DataFrame ---
data = []
for name, values in groups.items():
    for v in values:
        data.append({'Group': name, 'Value': v})
df = pd.DataFrame(data)

# --- Step 1: Kruskal–Wallis test ---
H, p = kruskal(*groups.values())
print(f"Kruskal–Wallis H={H:.3f}, p={p:.3f}")

# --- Step 2: Pairwise Mann–Whitney U tests with Bonferroni correction ---
pairs = list(combinations(groups.keys(), 2))
results = []
for g1, g2 in pairs:
    stat, p_pair = mannwhitneyu(groups[g1], groups[g2])
    results.append([g1, g2, stat, p_pair])

df_results = pd.DataFrame(results, columns=['Group1', 'Group2', 'U', 'p'])
df_results['p_adj'] = np.minimum(df_results['p'] * len(pairs), 1.0)  # Bonferroni

print("\nPairwise Mann–Whitney U (Bonferroni corrected):")
print(df_results.to_string(index=False))

# --- Step 3: Plot the data ---
plt.figure(figsize=(6, 5))
sns.boxplot(data=df, x='Group', y='Value', palette='pastel')
sns.swarmplot(data=df, x='Group', y='Value', color='black', alpha=0.6, size=6)

# --- Step 4: Add significance markers ---
y_max = df['Value'].max()
height = 0.3  # spacing between bars
offset = y_max + height

for i, row in df_results.iterrows():
    g1, g2, p_adj = row['Group1'], row['Group2'], row['p_adj']

    # Choose the number of stars
    if p_adj < 0.001:
        stars = '***'
    elif p_adj < 0.01:
        stars = '**'
    elif p_adj < 0.05:
        stars = '*'
    else:
        stars = 'ns'

    x1, x2 = list(groups.keys()).index(g1), list(groups.keys()).index(g2)
    y = offset + i * height  # increase height per line
    plt.plot([x1, x1, x2, x2], [y, y+0.05, y+0.05, y], lw=1.5, color='black')
    plt.text((x1+x2)/2, y+0.07, stars, ha='center', va='bottom', fontsize=12)

plt.title("Group Comparison (Kruskal–Wallis + Mann–Whitney post hoc)")
plt.tight_layout()
plt.show()
