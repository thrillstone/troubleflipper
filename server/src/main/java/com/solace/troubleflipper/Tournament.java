package com.solace.troubleflipper;

import com.solace.troubleflipper.configuration.SubscriptionHandler;
import com.solace.troubleflipper.messages.*;
import com.solace.troubleflipper.model.*;
import com.solace.troubleflipper.properties.BadGuyActionHandler;
import com.solace.troubleflipper.properties.TournamentProperties;
import com.solacesystems.jcsmp.JCSMPErrorResponseException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.*;
import java.util.stream.Collectors;

@Component
public class Tournament implements GameOverListener, BadGuyActionHandler {

    private static List<String> PUZZLE_NAMES = Arrays.asList("puzzle5.jpg", "puzzle-cookies-icing.jpg", "puzzle4.jpg", "puzzle-brick-loading-docs.jpg",  "puzzle1.jpg", "puzzle-Ian-is-not-Santa.jpg", "puzzle2.jpg", "puzzle3.jpg");

    private Logger log = LoggerFactory.getLogger("tournament");

    private final List<Player> players = new ArrayList<>();
    private volatile boolean gameStarted = false;
    private volatile boolean tournamentStarted = false;
    private int waitCounter = 10;
    private boolean resetWaitCounter = false;
    private Map<String, Team> teams = new HashMap<>();
    private Map<String, Game> activeGames = new HashMap<>();
    private Map<String, Collection<Game>> completedGames = new HashMap<>();
    private final LinkedList<Team> teamRankings = new LinkedList<>();
    private final LinkedList<Player> playerRankings = new LinkedList<>();

    private final Subscriber subscriber;
    private final Publisher publisher;

    private final TournamentProperties tournamentProperties;

    private Timer timer = new Timer("TournamentTimer");
    private Random randomGen = new Random();

    private final Object tournamentLock = new Object();

    @Autowired
    public Tournament(TournamentProperties tournamentProperties,
                      Subscriber subscriber, Publisher publisher) {
        this.tournamentProperties = tournamentProperties;
        this.subscriber = subscriber;
        this.publisher = publisher;
        subscriber.registerHandler(AddUserMessage.class, "users", this::addUser);
        subscriber.registerHandler(TournamentMessage.class, "tournaments", this::handleTournamentMsg);
    }

    @SubscriptionHandler(topic = "users", messageType = AddUserMessage.class)
    private void addUser(AddUserMessage addUserMessage) {
        if (addUserMessage.getGameMaster() != null && addUserMessage.getGameMaster().booleanValue() == true) {
            AddUserAckMessage addUserAckMessage = new AddUserAckMessage(addUserMessage, AddUserAckMessage.RESULT_SUCCESS);
            try {
                String clientName = addUserAckMessage.getClientId();
                publisher.publish("user/" + clientName, addUserAckMessage);
                log.info("Game master with id " + clientName + " has been registered in the tournament");
                try {
                    subscriber.subscribeForClient("tournament/>", clientName);
                    synchronized (tournamentLock) {
                        updateTournamentMessage();
                    }
                } catch (SubscriberException ex) {
                    log.error("Unable to register subscription for game master " + addUserAckMessage.getClientId(), ex);
                }

            } catch (PublisherException ex) {
                log.error("Unable to acknowledge game master " + addUserAckMessage.getClientId(), ex);
            }

        } else {
            synchronized (tournamentLock) {
                Optional<Player> firstMatch = players.stream().filter(item ->
                        addUserMessage.getClientId().equals(item.getClientName())).findFirst();
                Player player;
                boolean present = false;
                if (firstMatch.isPresent()) {
                    present = true;
                    player = firstMatch.get();
                    player.setGamerTag(addUserMessage.getUsername());
                    log.trace("Player " + player.getClientName() + " is already registered");
                } else {
                    player = new Player();
                    player.setGamerTag(addUserMessage.getUsername());
                    player.setClientName(addUserMessage.getClientId());
                    players.add(player);
                }
                AddUserAckMessage addUserAckMessage = new AddUserAckMessage(addUserMessage, AddUserAckMessage.RESULT_SUCCESS);
                try {
                    publisher.publish("user/" + player.getClientName(), addUserAckMessage);
                    log.info("Player " + player.getGamerTag() + " with id " + player.getClientName() + " has been registered in the tournament");
                    updateTournamentMessage();
                    if (present) {
                        // check if there is active game and send out team information
                        Optional<Game> firstMatchGame = activeGames.values().stream().filter(item ->
                                item.getTeam().getPlayer(player.getClientName()) != null).findFirst();
                        if (firstMatchGame.isPresent()) {
                            Game activeGame = firstMatchGame.get();
                            log.info("Player is part of a game");
                            boolean playerNotFound = false;
                            try {
                                subscriber.subscribeForClient("team/" + player.getTeam().getId(), player.getClientName());
                                subscriber.subscribeForClient("score/" + player.getTeam().getId(), player.getClientName());
                                subscriber.subscribeForClient("score/" + player.getClientName(), player.getClientName());
                            } catch (SubscriberException ex) {
                                if (ex.getCause() instanceof JCSMPErrorResponseException) {
                                    if (((JCSMPErrorResponseException) ex.getCause()).getResponseCode() == 404) {
                                        log.info("Player has lost connection, remove player from team and players list");
                                        playerNotFound = true;
                                        playerRankings.remove(player);
                                        activeGame.getTeam().removePlayer(player);
                                        this.players.remove(player);
                                    }
                                }
                                if (!playerNotFound) {
                                    // unrecoverable, player cannot receive any message
                                    log.error("Unable to register subscription for " + player.getClientName() + " on team " + player.getTeam().getId(), ex);
                                }
                            }

                            if (tournamentStarted) {
                                if ((player.getCharacter() == null && !playerNotFound) || !gameStarted) {
                                    activeGame.updateCharactersForTeam(false);
                                } else {
                                    activeGame.updatePuzzleForTeam(false);
                                }
                            }
                        }
                    }
                } catch (PublisherException ex) {
                    log.error("Unable to acknowledge player " + player.getClientName(), ex);
                    players.remove(player);
                    // TODO also update game related information for this player
                }
            }
        }
    }

    @SubscriptionHandler(topic = "tournaments", messageType = TournamentMessage.class)
    private void handleTournamentMsg(TournamentMessage tournamentMessage) {
        if (tournamentMessage.getAction().equals("buildTeams")) {
            synchronized (tournamentLock) {
                if (players.size() > 0) {
                    if (tournamentStarted) {
                        log.info("Tournament is already in progress");
                        return;
                    }
                    log.info("Start tournament");
                    tournamentStarted = true;
                    prepareTeams();
                    for (Game game : activeGames.values()) {
                        game.addGameOverListener(this);
                        game.updateCharactersForTeam(false);
                    }
                    updateTournamentMessage();
                }
            }
        } else if (tournamentMessage.getAction().equals("stopGames")) {
            synchronized (tournamentLock) {
                if (!tournamentStarted) {
                    log.info("Tournament stop in progress");
                    return;
                }
                log.info("Stop tournament for " + activeGames.size() + " teams");
                tournamentStarted = false;
                gameStarted = false;
                timer.cancel();
                timer.purge();
                List<Game> gamesWon = new ArrayList<>();
                for (Game game : activeGames.values()) {
                    log.info("stop game for team " + game.getTeam().getId());
                    if (game.stop()) {
                        gamesWon.add(game);
                    }
                    game.updatePuzzleForTeam(true);
                }
                if (gamesWon.size() > 0) {
                    gamesWon.forEach(game -> {
                        Team team = game.getTeam();
                        log.info("Game won, update completed game for " + team.getId() + ", " + team.getName());
                        activeGames.remove(team.getId());
                        completedGames.get(team.getId()).add(game);
                        team.addCompletedGame();
                    });;
                }
                updateTournamentMessage();
            }
        }
    }

    private String getPuzzleName(Team team) {
        String puzzleName = null;
        if (team != null) {
            puzzleName = team.chooseNextPuzzleName();
            log.info("Next puzzle " + puzzleName + " from " + team.getPuzzleNames().toString());
        }
        if (puzzleName == null) {
            int index = randomGen.nextInt(PUZZLE_NAMES.size());
            puzzleName = PUZZLE_NAMES.get(index);
            log.info("Random puzzle name " + puzzleName);
        }
        return puzzleName;
    }

    // call inside synchronized(tournamentLock)
    private void prepareTeams() {
        waitCounter = 10;
        resetWaitCounter = false;
        teams.clear();
        activeGames.clear();
        completedGames.clear();
        teamRankings.clear();
        playerRankings.clear();
        tournamentProperties.resetTeamNamesUsed();

        timer.cancel();
        timer.purge();
        timer = new Timer("TournamentTimer");
        if (players.size() == 0) {
            return;
        }
        int numberOfPlayers = players.size();
        int playersPerTeam = tournamentProperties.getPlayersPerTeam();
        int numberOfTeams = (int)Math.ceil(players.size() / playersPerTeam);
        log.debug("numberOfTeams " + numberOfTeams);
        List<Player> teamPlayers = null;
        String teamName = null;
        Player player;
        List<Player> allLeftPlayers = new ArrayList<>();
        for (int i = 0; i < numberOfPlayers; i++) {
            if (teamPlayers == null) {
                teamPlayers = new ArrayList<>();
            }
            player =  players.get(i);
            player.reset();
            teamPlayers.add(player);
            if (teamPlayers.size() == playersPerTeam || i == numberOfPlayers - 1) {
                teamName = tournamentProperties.getNewTeamName();
                List<Player> leftPlayers = addTeam(teamName, teamPlayers);
                teamPlayers = null;
                if (leftPlayers != null) {
                    allLeftPlayers.addAll(leftPlayers);
                }
            }
        }
        if (allLeftPlayers.size() > 0) {
            for (Player leftPlayer : allLeftPlayers) {
                log.info("Remove " + leftPlayer.getGamerTag() + ", " + leftPlayer.getClientName() + " from players list because it is not connected");
                this.players.remove(leftPlayer);
            }
        }

        timer.schedule(new TimerTask() {
            @Override
            public void run() {
                synchronized (tournamentLock) {
                    if (!tournamentStarted) {
                        this.cancel();
                        return;
                    }
                    log.info("check character selection readiness");
                    waitCounter--;
                    if (waitCounter > 0) {
                        int totalGames = activeGames.size();
                        int readyGames = 0;
                        for (Game game : activeGames.values()) {
                            if (game.isCharacterReadyForTeam()) {
                                readyGames++;
                            }
                        }
                        if (readyGames == totalGames) {
                            this.cancel();
                            log.info("All games have selected characters");
                            startGames();
                        }
                    } else {
                        if (resetWaitCounter) {
                            this.cancel();
                            log.info("character assignment unsuccessful");
                        }
                        try {
                            log.info("force assign characters");
                            for (Game game : activeGames.values()) {
                                // send special pickCharacter message to avoid synchronization on team object
                                PickCharacterMessage pickCharacterMessage = new PickCharacterMessage();
                                pickCharacterMessage.setClientId("");
                                pickCharacterMessage.setCharacterType(CharacterType.mario);
                                publisher.publish("games/" + game.getTeam().getId() + "/pickCharacter", pickCharacterMessage);
                            }
                            // give it two more run
                            waitCounter = 5;
                            resetWaitCounter = true;
                        } catch (PublisherException ex) {
                            log.error("Unable to publish pick character message for game", ex);
                            this.cancel();
                        }
                    }
                }
            }
        }, 0, 2000);

    }

    // call from synchronized(tournamentLock)
    private void startGames() {
        log.info("startGames");
        gameStarted = true;
        for (Game game : activeGames.values()) {
            game.start();
            game.updatePuzzleForTeam(false);
        }

        timer.schedule(new TimerTask() {
            @Override
            public void run() {
                if (!tournamentStarted || !gameStarted) {
                    this.cancel();
                    return;
                }
                playerRankings.sort((player1, player2) -> {
                    int player1Score = player1.getRightMoves() - player1.getWrongMoves();
                    int player2Score = player2.getRightMoves() - player2.getWrongMoves();
                    return player2Score - player1Score;
                });
                for (int i = 0; i < playerRankings.size(); ++i) {
                    Player player = playerRankings.get(i);
                    PlayerRankMessage playerRankMessage = new PlayerRankMessage();
                    playerRankMessage.setRank(i + 1);
                    playerRankMessage.setId(player.getClientName());
                    playerRankMessage.setTotalPlayers(playerRankings.size());
                    try {
                        publisher.publish("score/" + player.getClientName(), playerRankMessage);
                    } catch (PublisherException ex) {
                        log.error("Unable to update the scores for players", ex);
                    }
                }
                PlayerListMessage playersMessage = new PlayerListMessage();
                playersMessage.setPlayers(playerRankings);

                List<Map<String, String>> teams =
                        teamRankings.stream().map(team -> {
                            Map<String, String>teamMessage = new HashMap<>();
                            teamMessage.put("id", team.getId());
                            teamMessage.put("name", team.getName());
                            teamMessage.put("completed", new Integer(team.getCompletedGames()).toString());

                            if (team.getGame() != null) {
                                teamMessage.put("game", team.getGame().getPuzzleName());
                            }
                            return teamMessage;

                        }).collect(Collectors.toList());
                playersMessage.setTeams(teams);
                try {
                    publisher.publish("score/players", playersMessage);
                } catch (PublisherException ex) {
                    log.error("Unable to send player to score/players", ex);
                }


            }
        }, 0, 5000);

        timer.schedule(new TimerTask() {
            @Override
            public void run() {
                if (!tournamentStarted || !gameStarted) {
                    this.cancel();
                    return;
                }
                synchronized (teamRankings) {
                    teamRankings.sort((team1, team2) -> {
                        int team1Winning = team2.getCompletedGames() - team1.getCompletedGames();
                        if (team1Winning == 0) {
                            team1Winning = team2.getGame().getCorrectPieces() - team1.getGame().getCorrectPieces();
                        }
                        return team1Winning;
                    });
                    for (int i = 0; i < teamRankings.size(); ++i) {
                        Team team = teamRankings.get(i);
                        TeamRankMessage teamRankMessage = new TeamRankMessage();
                        teamRankMessage.setRank(i + 1);
                        teamRankMessage.setTeamId(team.getId());
                        teamRankMessage.setTotalTeams(teamRankings.size());
                        try {
                            publisher.publish("score/" + team.getId(), teamRankMessage);
                        } catch (PublisherException ex) {
                            log.error("Unable to update the scores", ex);
                        }
                    }
                }
            }
        }, 0 , 3000);
    }

    private List<Player> addTeam(String teamName, Collection<Player> players) {
        Team team = new Team();
        List<Player> leftPlayers = null;

        if (teamName == null) {
            team.setName(team.getId());
        } else {
            team.setName(teamName);
        }
        team.setPuzzleNames(new ArrayList<String>(PUZZLE_NAMES));
        completedGames.put(team.getId(), new ArrayList<>());
        Game game = new Game(team, subscriber, publisher, timer, tournamentProperties, this);
        game.setPuzzleName(getPuzzleName(team));
        team.setGame(game);
        for (Player player : players) {
            team.addPlayer(player);
            player.setTeam(team);
        }

        teams.put(team.getId(), team);
        teamRankings.add(team);

        activeGames.put(team.getId(), game);

        for (Player player : players) {
            playerRankings.add(player);
            try {
                subscriber.subscribeForClient("team/" + player.getTeam().getId(), player.getClientName());
                subscriber.subscribeForClient("score/" + player.getTeam().getId(), player.getClientName());
                subscriber.subscribeForClient("score/" + player.getClientName(), player.getClientName());
            } catch (SubscriberException ex) {
                boolean playerNotFound = false;
                if (ex.getCause() instanceof JCSMPErrorResponseException) {
                    if (((JCSMPErrorResponseException) ex.getCause()).getResponseCode() == 404) {
                        log.info("Player has lost connection, remove player from team and players list");
                        playerNotFound = true;
                        playerRankings.remove(player);
                        team.removePlayer(player);
                        if (leftPlayers == null) {
                            leftPlayers = new ArrayList<>();
                        }
                        leftPlayers.add(player);
                    }
                }
                if (!playerNotFound) {
                    log.error("Unable to register subscription for " + player.getClientName() + " on team " + player.getTeam().getId(), ex);
                }
            }
        }

        return leftPlayers;
    }

    private void updateTournamentMessage() {
        List<Player> waitingPlayers = new ArrayList<>();
        List<Team> teams = new ArrayList<>();
        this.players.forEach(player -> {
            if (player.getTeam() == null) {
                waitingPlayers.add(player);
            } else {
                if (!teams.contains(player.getTeam())) {
                    teams.add(player.getTeam());
                }
            }
        });
        UpdateTournamentMessage updateTournamentMessage = new UpdateTournamentMessage();
        updateTournamentMessage.setStarted(tournamentStarted);
        updateTournamentMessage.setWaitingPlayers(waitingPlayers);
        updateTournamentMessage.setTeams(teams);
        try {
            log.info("Publish tournament update message, tournamentStarted " + tournamentStarted);
            publisher.publish("tournament/update", updateTournamentMessage);
        } catch (PublisherException ex) {
            log.error("Unable to update the tournament update message", ex);
        }
    }

    @Override
    public void gameOver(Game game) {
        synchronized (tournamentLock) {
            String teamId = game.getTeam().getId();
            if (tournamentStarted && gameStarted && game.isGameOver()) {
                log.info("Game won, update completed game for " + teamId + ", " + game.getTeam().getName());
                Team team = teams.get(teamId);
                activeGames.remove(teamId);
                completedGames.get(teamId).add(game);
                team.addCompletedGame();

                Game newGame = new Game(team, subscriber, publisher, timer, tournamentProperties, this);
                newGame.setPuzzleName(getPuzzleName(team));
                team.setGame(newGame);
                timer.schedule(new TimerTask() {
                    @Override
                    public void run() {
                        if (tournamentStarted && gameStarted) {
                            activeGames.put(teamId, newGame);
                            newGame.addGameOverListener(Tournament.this);
                            newGame.start();
                            newGame.updatePuzzleForTeam(false);
                        }
                    }
                }, 3000);
            }
        }
    }

    @Override
    public void troubleFlipper(Player bowserPlayer) {
        synchronized (teamRankings) {
            if (tournamentStarted && gameStarted) {
                int rank = teamRankings.indexOf(bowserPlayer.getTeam());
                if (rank == 0) {
                    log.info("Bowser from team " + bowserPlayer.getTeam().getName() + " used trouble flipper, but they are in first place");
                } else {
                    int indexOfTeamToAttack = rank - 1;
                    Team teamToAttack = teamRankings.get(indexOfTeamToAttack);
                    teamToAttack.getGame().troubleFlipper(bowserPlayer);
                    Game attackerGame = bowserPlayer.getTeam().getGame();
                    if (attackerGame != null) {
                        attackerGame.updatePuzzleForTeam(false);
                    }
                }
            }
        }
    }

    @Override
    public void greenShell(Player goombaPlayer) {
        synchronized (teamRankings) {
            if (tournamentStarted && gameStarted) {
                int rank = teamRankings.indexOf(goombaPlayer.getTeam());
                if (rank == 0) {
                    log.info("Goomba from team " + goombaPlayer.getTeam().getName() + " used a green shell, but they are in first place");
                } else {
                    int indexOfTeamToAttack = rank - 1;
                    Team teamToAttack = teamRankings.get(indexOfTeamToAttack);
                    teamToAttack.getGame().greenShell();
                    Game attackerGame = goombaPlayer.getTeam().getGame();
                    if (attackerGame != null) {
                        attackerGame.updatePuzzleForTeam(false);
                    }
                }
            }
        }
    }
}
