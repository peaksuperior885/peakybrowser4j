const searchForm = document.getElementById("search-form");
const searchInput = document.getElementById("search-input");
const clearButton = document.getElementById("clear-button");
const luckyButton = document.getElementById("lucky-button");

function searchGoogle(query) {
    query = query.trim();

    if (!query) {
        searchInput.focus();
        return;
    }

    const url =
        "https://www.google.com/search?q=" +
        encodeURIComponent(query);

    window.location.href = url;
}

searchForm.addEventListener("submit", function (event) {
    event.preventDefault();

    searchGoogle(searchInput.value);
});

searchInput.addEventListener("input", function () {
    clearButton.hidden = searchInput.value.length === 0;
});

clearButton.addEventListener("click", function () {
    searchInput.value = "";
    clearButton.hidden = true;
    searchInput.focus();
});

luckyButton.addEventListener("click", function () {
    searchGoogle(searchInput.value);
});

searchInput.focus();