from csv import DictReader
import matplotlib.pyplot as plt
from geopy.distance import geodesic
from math import atan2, radians, degrees, sin, cos
import datetime


def read_csv(file_path):
    """Reads a CSV file and returns the data as a list of lists."""
    with open(file_path, 'r') as file:
        dict_reader = DictReader(file)
        return list(dict_reader)


if __name__ == "__main__":
    # result = read_csv("36748412_2025_08_06_16_48_10.csv")
    # result = read_csv("36757301_2025_08_06_16_24_37.csv")
    result = read_csv("37427597_2025_08_06_15_51_43.csv")

    start_timestamp = float(result[0]["UPDATETIMESTAMP"])
    end_timestamp = float(result[-1]["UPDATETIMESTAMP"])

    tracking_time = (end_timestamp - start_timestamp) / 1000  # in seconds
    gps_returning_frequency = len(result) / tracking_time  # in Hz
    print("GPS returning frequency:", gps_returning_frequency)
    print("Tracking time:", tracking_time)
    print(
        f'From {datetime.datetime.fromtimestamp(start_timestamp/1000).isoformat()} to {datetime.datetime.fromtimestamp(end_timestamp/1000).isoformat()}')

    # 1. List of GPS positions (latitude, longitude)
    gps_points = [(float(x['DRONELATITUDE']), float(x['DRONELONGITUDE'])) for x in result[:600]]

    # 2. Choose an origin
    origin = (float(result[0]['BENCHMARKLATITUDE']), float(result[0]['BENCHMARKLONGITUDE']))


    # 3. Function to compute bearing from origin to a point
    def calculate_bearing(start, end):
        lat1, lon1 = radians(start[0]), radians(start[1])
        lat2, lon2 = radians(end[0]), radians(end[1])
        dlon = lon2 - lon1

        x = sin(dlon) * cos(lat2)
        y = cos(lat1) * sin(lat2) - sin(lat1) * cos(lat2) * cos(dlon)

        bearing = atan2(x, y)
        bearing = degrees(bearing)
        return (bearing + 360) % 360  # Normalize to 0–360°


    # 4. Convert GPS to polar coords (distance, bearing)
    polar_points = []
    for point in gps_points[1:]:  # Skip origin itself
        distance = geodesic(origin, point).meters
        bearing = calculate_bearing(origin, point)
        polar_points.append((distance, bearing, point))

    # 5. Convert polar (distance, bearing) → Cartesian (x, y)
    xy_points = []
    for distance, bearing, point in polar_points:
        angle_rad = radians(bearing)
        x = distance * cos(angle_rad)
        y = distance * sin(angle_rad)
        xy_points.append((x, y, point))

    # 6. Plot in 2D
    plt.figure(figsize=(10, 10))
    plt.axhline(0, color='gray', linestyle='--')
    plt.axvline(0, color='gray', linestyle='--')

    # Origin at (0,0)
    plt.plot(0, 0, 'ro')
    plt.text(0, 0, 'Origin', fontsize=10, ha='right')

    # Plot all other points
    for x, y, point in xy_points:
        plt.plot(x, y, 'bo')
        # plt.text(x, y, f"{point}", fontsize=9)

    plt.title('Relative Positions from Origin (Radial Map)')
    plt.xlabel('East-West (m)')
    plt.ylabel('North-South (m)')
    plt.axis('equal')
    plt.grid(True)
    plt.legend()
    plt.show()
